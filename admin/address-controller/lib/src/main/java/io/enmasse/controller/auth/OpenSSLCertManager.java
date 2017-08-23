/*
 * Copyright 2017 Red Hat Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.enmasse.controller.auth;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import io.enmasse.config.LabelKeys;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.openshift.client.OpenShiftClient;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller that creates self-signed certificates for instances.
 */
public class OpenSSLCertManager implements CertManager {
    private static final Logger log = LoggerFactory.getLogger(OpenSSLCertManager.class.getName());
    private final OpenShiftClient client;
    private final File caKey;
    private final File caCert;
    private final File certDir;
    private final String globalNamespace;

    public OpenSSLCertManager(OpenShiftClient controllerClient,
                              File caKey,
                              File caCert,
                              File certDir,
                              final String globalNamespace) {
        this.client = controllerClient;
        this.caKey = caKey;
        this.caCert = caCert;
        this.certDir = certDir;
        this.globalNamespace = globalNamespace;
    }

    @Override
    public void issueRouteCert(String secretName, String namespace, String ... hostnames) throws Exception {
        Secret secret = client.secrets().inNamespace(namespace).withName(secretName).get();
        if (secret != null) {
            // TODO: Have this sign certificates with OpenShift CA

            String keyKey = "server-key.pem";
            String certKey = "server-cert.pem";
            if (secret.getData() != null && secret.getData().containsKey(keyKey) && secret.getData().containsKey(certKey)) {
                return;
            }

            log.info("Creating self-signed certificates for " + Arrays.toString(hostnames));
            File keyFile = new File("/tmp/server-key.pem");
            File certFile = new File("/tmp/server-cert.pem");

            createSelfSignedCert(keyFile, certFile);
            createSecretFromCertAndKeyFiles(secretName, namespace, keyKey, certKey, keyFile, certFile, client);
        }
    }

    private static void createSelfSignedCert(final File keyFile, final File certFile) {
        runCommand("openssl", "req", "-new", "-days", "11000", "-x509", "-batch", "-nodes",
                "-out", certFile.getAbsolutePath(), "-keyout", keyFile.getAbsolutePath());
    }

    private static void createSecretFromCertAndKeyFiles(final String secretName,
                                                        final String namespace,
                                                        final File keyFile,
                                                        final File certFile,
                                                        final OpenShiftClient client)
            throws IOException {
        createSecretFromCertAndKeyFiles(secretName, namespace, "tls.key", "tls.crt", keyFile, certFile, client);
    }

    private static void createSecretFromCertAndKeyFiles(final String secretName,
                                                        final String namespace,
                                                        final String keyKey,
                                                        final String certKey,
                                                        final File keyFile,
                                                        final File certFile,
                                                        final OpenShiftClient client)
            throws IOException {
        Map<String, String> data = new LinkedHashMap<>();
        Base64.Encoder encoder = Base64.getEncoder();
        data.put(keyKey, encoder.encodeToString(FileUtils.readFileToByteArray(keyFile)));
        data.put(certKey, encoder.encodeToString(FileUtils.readFileToByteArray(certFile)));
        client.secrets().inNamespace(namespace).withName(secretName).createOrReplaceWithNew()
                .editOrNewMetadata()
                .withName(secretName)
                .endMetadata()
                .addToData(data)
                .done();
    }

    private static void runCommand(String ... cmd) {
        ProcessBuilder keyGenBuilder = new ProcessBuilder(cmd).redirectErrorStream(true);

        log.info("Running command '{}'", keyGenBuilder.command());
        Process keyGen = null;
        try {
            keyGen = keyGenBuilder.start();
            if (!keyGen.waitFor(1, TimeUnit.MINUTES)) {
                throw new RuntimeException("Command timed out");
            }
        } catch (InterruptedException ignored) {
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Collection<CertComponent> listComponents(String namespace) {
        return client.extensions().deployments().inNamespace(namespace).withLabel(LabelKeys.CERT_SECRET_NAME).list().getItems().stream()
                .map(deployment -> new CertComponent(deployment.getMetadata().getName(), namespace, deployment.getMetadata().getLabels().get(LabelKeys.CERT_SECRET_NAME)))
                .collect(Collectors.toList());
    }

    @Override
    public boolean certExists(CertComponent component) {
        return client.secrets().inNamespace(component.getNamespace()).withName(component.getSecretName()).get() != null;
    }

    @Override
    public boolean certExists(String name) {
        return client.secrets().withName(name).get() != null;
    }


    @Override
    public CertSigningRequest createCsr(CertComponent component) {
        File keyFile = new File(certDir, component.getNamespace() + "." + component.getName() + ".key");
        File csrFile = new File(certDir, component.getNamespace() + "." + component.getName() + ".csr");
        runCommand("openssl", "req", "-new", "-batch", "-nodes", "-keyout", keyFile.getAbsolutePath(), "-subj", "\"/O=io.enmasse/CN=" + component.getName() + "\"", "-out", csrFile.getAbsolutePath());
        return new CertSigningRequest(component, csrFile, keyFile);
    }

    @Override
    public Cert signCsr(CertSigningRequest request, String secretName) {
        File crtFile = new File(certDir, request.getCertComponent().getNamespace() + "." + request.getCertComponent().getName() + ".crt");
        final Secret secret = client.secrets().withName(secretName).get();

        File caKey = createTempFileFromSecret(secret, "tls.key");
        File caCert = createTempFileFromSecret(secret, "tls.crt");

        try {
            runCommand("openssl",
                       "x509",
                       "-req",
                       "-days",
                       "11000",
                       "-in",
                       request.getCsrFile().getAbsolutePath(),
                       "-CA",
                       caCert.getAbsolutePath(),
                       "-CAkey",
                       caKey.getAbsolutePath(),
                       "-CAcreateserial",
                       "-out",
                       crtFile.getAbsolutePath());
            return new Cert(request.getCertComponent(), request.getKeyFile(), crtFile);
        } finally {
            caKey.delete();
            caCert.delete();
        }

    }

    private File createTempFileFromSecret(Secret secret, String entry) {
        try {
            File file = File.createTempFile("secret", "pem");
            String data = secret.getData().get(entry);
            final Base64.Decoder decoder = Base64.getDecoder();
            FileOutputStream outputStream = new FileOutputStream(file);
            outputStream.write(decoder.decode(data));
            outputStream.close();
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void createSecret(Cert cert, final String caSecretName) {
        try {
            Map<String, String> data = new LinkedHashMap<>();
            Base64.Encoder encoder = Base64.getEncoder();
            data.put("tls.key", encoder.encodeToString(FileUtils.readFileToByteArray(cert.getKeyFile())));
            data.put("tls.crt", encoder.encodeToString(FileUtils.readFileToByteArray(cert.getCertFile())));
            Secret caSecret = client.secrets().inNamespace(this.globalNamespace).withName(caSecretName).get();
            data.put("ca.crt", caSecret.getData().get("tls.crt"));

            client.secrets().inNamespace(cert.getComponent().getNamespace()).createNew()
                    .editOrNewMetadata()
                    .withName(cert.getComponent().getSecretName())
                    .endMetadata()
                    .withType("kubernetes.io/tls")
                    .addToData(data)
                    .done();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void createSelfSignedCertSecret(final String secretName) {
        try {
            File key = File.createTempFile("tls", "key");
            File cert = File.createTempFile("tls", "crt");
            try {
                createSelfSignedCert(key, cert);
                createSecretFromCertAndKeyFiles(secretName, this.globalNamespace, key, cert, this.client);
            } finally {
                key.delete();
                cert.delete();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static OpenSSLCertManager create(OpenShiftClient controllerClient,
                                            File caDir,
                                            final String globalNamespace) {
        File caKey = new File(caDir, "tls.key");
        File caCrt = new File(caDir, "tls.crt");
        return new OpenSSLCertManager(controllerClient, caKey, caCrt, new File("/tmp"), globalNamespace);
    }

}
