/*
 * Copyright (c) 2016 Kevin Herron
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 *
 * The Eclipse Public License is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *   http://www.eclipse.org/org/documents/edl-v10.html.
 */

package org.eclipse.milo.examples.server;

import java.io.File;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.api.config.OpcUaServerConfig;
import org.eclipse.milo.opcua.sdk.server.identity.CompositeValidator;
import org.eclipse.milo.opcua.sdk.server.identity.UsernameIdentityValidator;
import org.eclipse.milo.opcua.sdk.server.identity.X509IdentityValidator;
import org.eclipse.milo.opcua.sdk.server.util.HostnameUtil;
import org.eclipse.milo.opcua.stack.core.application.DefaultCertificateManager;
import org.eclipse.milo.opcua.stack.core.application.DirectoryCertificateValidator;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.structured.BuildInfo;
import org.eclipse.milo.opcua.stack.core.util.CertificateUtil;
import org.eclipse.milo.opcua.stack.server.EndpointConfiguration;
import org.slf4j.LoggerFactory;

import static com.google.common.collect.Lists.newArrayList;
import static org.eclipse.milo.opcua.sdk.server.api.config.OpcUaServerConfig.USER_TOKEN_POLICY_ANONYMOUS;
import static org.eclipse.milo.opcua.sdk.server.api.config.OpcUaServerConfig.USER_TOKEN_POLICY_USERNAME;
import static org.eclipse.milo.opcua.sdk.server.api.config.OpcUaServerConfig.USER_TOKEN_POLICY_X509;

public class ExampleServer {

    static {
        // Required for SecurityPolicy.Aes256_Sha256_RsaPss
        Security.addProvider(new BouncyCastleProvider());
    }

    public static void main(String[] args) throws Exception {
        ExampleServer server = new ExampleServer();

        server.startup().get();

        final CompletableFuture<Void> future = new CompletableFuture<>();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> future.complete(null)));

        future.get();
    }

    private final OpcUaServer server;

    public ExampleServer() throws Exception {
        File securityTempDir = new File(System.getProperty("java.io.tmpdir"), "security");
        if (!securityTempDir.exists() && !securityTempDir.mkdirs()) {
            throw new Exception("unable to create security temp dir: " + securityTempDir);
        }
        LoggerFactory.getLogger(getClass()).info("security temp dir: {}", securityTempDir.getAbsolutePath());

        KeyStoreLoader loader = new KeyStoreLoader().load(securityTempDir);

        DefaultCertificateManager certificateManager = new DefaultCertificateManager(
            loader.getServerKeyPair(),
            loader.getServerCertificateChain()
        );

        File pkiDir = securityTempDir.toPath().resolve("pki").toFile();
        DirectoryCertificateValidator certificateValidator = new DirectoryCertificateValidator(pkiDir);
        LoggerFactory.getLogger(getClass()).info("pki dir: {}", pkiDir.getAbsolutePath());

        UsernameIdentityValidator identityValidator = new UsernameIdentityValidator(
            true,
            authChallenge -> {
                String username = authChallenge.getUsername();
                String password = authChallenge.getPassword();

                boolean userOk = "user".equals(username) && "password1".equals(password);
                boolean adminOk = "admin".equals(username) && "password2".equals(password);

                return userOk || adminOk;
            }
        );

        X509IdentityValidator x509IdentityValidator = new X509IdentityValidator(c -> true);

        // If you need to use multiple certificates you'll have to be smarter than this.
        Optional<X509Certificate> firstCertificate = certificateManager.getCertificates().stream().findFirst();

        List<String> bindAddresses = newArrayList();
        bindAddresses.add("0.0.0.0");

        List<String> hostnames = newArrayList();
        hostnames.add(HostnameUtil.getHostname());
        hostnames.addAll(HostnameUtil.getHostnames("0.0.0.0"));

        Set<EndpointConfiguration> endpointConfigurations = new LinkedHashSet<>();

        for (String bindAddress : bindAddresses) {
            for (String hostname : hostnames) {
                EndpointConfiguration.Builder base = EndpointConfiguration.newBuilder()
                    .setBindAddress(bindAddress)
                    .setBindPort(12686)
                    .setHostname(hostname)
                    .setPath("/example")
                    .setCertificate(firstCertificate.orElseThrow(
                        () -> new IllegalArgumentException("certificate")))
                    .addTokenPolicies(
                        USER_TOKEN_POLICY_ANONYMOUS,
                        USER_TOKEN_POLICY_USERNAME,
                        USER_TOKEN_POLICY_X509);


                endpointConfigurations.add(
                    base.copy()
                        .setSecurityPolicy(SecurityPolicy.None)
                        .setSecurityMode(MessageSecurityMode.None)
                        .build()
                );

                endpointConfigurations.add(
                    base.copy()
                        .setSecurityPolicy(SecurityPolicy.Basic256Sha256)
                        .setSecurityMode(MessageSecurityMode.SignAndEncrypt)
                        .build()
                );

                /*
                 * It's good practice to provide a discovery-specific endpoint.
                 * It's required practice if all regular endpoints have security configured.
                 *
                 * Usage of the  "/discovery" suffix is defined by OPC UA Part 6:
                 *
                 * Each OPC UA Server Application implements the Discovery Service Set. If the OPC UA Server requires a
                 * different address for this Endpoint it shall create the address by appending the path "/discovery" to
                 * its base address.
                 */

                endpointConfigurations.add(
                    base.copy()
                        .setPath("/example/discovery")
                        .setSecurityPolicy(SecurityPolicy.None)
                        .setSecurityMode(MessageSecurityMode.None)
                        .build()
                );
            }
        }

        // The configured application URI must match the one in the certificate(s)
        String applicationUri = firstCertificate
            .map(certificate ->
                CertificateUtil.getSubjectAltNameField(certificate, CertificateUtil.SUBJECT_ALT_NAME_URI)
                    .map(Object::toString)
                    .orElseThrow(() -> new RuntimeException("certificate is missing the application URI")))
            .orElse("urn:eclipse:milo:examples:server:" + UUID.randomUUID());

        OpcUaServerConfig serverConfig = OpcUaServerConfig.builder()
            .setApplicationUri(applicationUri)
            .setApplicationName(LocalizedText.english("Eclipse Milo OPC UA Example Server"))
            .setEndpoints(endpointConfigurations)
            .setBuildInfo(
                new BuildInfo(
                    "urn:eclipse:milo:example-server",
                    "eclipse",
                    "eclipse milo example server",
                    OpcUaServer.SDK_VERSION,
                    "", DateTime.now()))
            .setCertificateManager(certificateManager)
            .setCertificateValidator(certificateValidator)
            .setIdentityValidator(new CompositeValidator(identityValidator, x509IdentityValidator))
            .setProductUri("urn:eclipse:milo:example-server")
            .build();

        server = new OpcUaServer(serverConfig);

        server.getNamespaceManager().registerAndAdd(
            ExampleNamespace.NAMESPACE_URI,
            idx -> new ExampleNamespace(server, idx));
    }

    public OpcUaServer getServer() {
        return server;
    }

    public CompletableFuture<OpcUaServer> startup() {
        return server.startup();
    }

    public CompletableFuture<OpcUaServer> shutdown() {
        return server.shutdown();
    }

}
