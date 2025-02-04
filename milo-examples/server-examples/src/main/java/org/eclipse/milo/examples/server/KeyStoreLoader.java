/*
 * Copyright (c) 2019 the Eclipse Milo Authors
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.eclipse.milo.examples.server;

import com.google.common.collect.Sets;
import org.eclipse.milo.opcua.sdk.server.util.HostnameUtil;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateBuilder;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

class KeyStoreLoader {

    private static final Pattern IP_ADDR_PATTERN = Pattern.compile(
            "^(([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\.){3}([01]?\\d\\d?|2[0-4]\\d|25[0-5])$");

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private X509Certificate[] serverCertificateChain;
    private X509Certificate serverCertificate;
    private KeyPair serverKeyPair;

    // Server-specific KeyStoreLoader customizations
    private static String commonName = "Eclipse Milo Example Server";
    private static String organization ="digitalpetri";
    private static String organizationalUnit ="dev";
    private static String locality = "Folsom";
    private static String state ="CA";
    private static String countryCode ="US";
    private static String applicationUri = "urn:eclipse:milo:examples:server:" + UUID.randomUUID();

    private static final String SERVER_ALIAS = "server-ai";
    private static final char[] PASSWORD = "umati9816760959@".toCharArray();

    KeyStoreLoader load(File baseDir) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");

        File serverKeyStore = baseDir.toPath().resolve("UT-UMATI-server.pfx").toFile();

        logger.info("Loading KeyStore at {}", serverKeyStore);

        if (!serverKeyStore.exists()) {
            keyStore.load(null, PASSWORD);

            KeyPair keyPair = SelfSignedCertificateGenerator.generateRsaKeyPair(2048);

            SelfSignedCertificateBuilder builder = new SelfSignedCertificateBuilder(keyPair)
                    .setCommonName(commonName)
                    .setOrganization(organization)
                    .setOrganizationalUnit(organizationalUnit)
                    .setLocalityName(locality)
                    .setStateName(state)
                    .setCountryCode(countryCode)
                    .setApplicationUri(applicationUri);

            // Get as many hostnames and IP addresses as we can listed in the certificate.
            Set<String> hostnames = Sets.union(
                    Sets.newHashSet(HostnameUtil.getHostname()),
                    HostnameUtil.getHostnames("0.0.0.0", false)
            );

            for (String hostname : hostnames) {
                if (IP_ADDR_PATTERN.matcher(hostname).matches()) {
                    builder.addIpAddress(hostname);
                } else {
                    builder.addDnsName(hostname);
                }
            }

            X509Certificate certificate = builder.build();

            keyStore.setKeyEntry(SERVER_ALIAS, keyPair.getPrivate(), PASSWORD, new X509Certificate[]{certificate});
            keyStore.store(new FileOutputStream(serverKeyStore), PASSWORD);
        } else {
            keyStore.load(new FileInputStream(serverKeyStore), PASSWORD);
        }

        Key serverPrivateKey = keyStore.getKey(SERVER_ALIAS, PASSWORD);
        if (serverPrivateKey instanceof PrivateKey) {
            serverCertificate = (X509Certificate) keyStore.getCertificate(SERVER_ALIAS);

            serverCertificateChain = Arrays.stream(keyStore.getCertificateChain(SERVER_ALIAS))
                    .map(X509Certificate.class::cast)
                    .toArray(X509Certificate[]::new);

            PublicKey serverPublicKey = serverCertificate.getPublicKey();
            serverKeyPair = new KeyPair(serverPublicKey, (PrivateKey) serverPrivateKey);
        }

        return this;
    }

    X509Certificate getServerCertificate() {
        return serverCertificate;
    }

    public X509Certificate[] getServerCertificateChain() {
        return serverCertificateChain;
    }

    KeyPair getServerKeyPair() {
        return serverKeyPair;
    }

    public static void customize(String commonName, String organization, String organizationalUnit,
                                 String state, String countryCode, String applicationUri) {
        KeyStoreLoader.commonName = commonName;
        KeyStoreLoader.organization = organization;
        KeyStoreLoader.organizationalUnit = organizationalUnit;
        KeyStoreLoader.state = state;
        KeyStoreLoader.countryCode = countryCode;
        KeyStoreLoader.applicationUri = applicationUri;
    }
}
