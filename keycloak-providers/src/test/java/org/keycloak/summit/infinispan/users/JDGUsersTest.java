package org.keycloak.summit.infinispan.users;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.models.Constants;
import org.keycloak.representations.idm.FederatedIdentityRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

/**
 * Do some CRUD of users and federationLinks and assert that users are visible on all 3 clusters (AWS, AZR, GCE)
 *
 * It assumes that "secretstuff" repository was checkout from github and has same parent as "rh-sso" repository. The projectName and adminPassword
 * are retrieved from there
 *
 * Other configurations are available with configuration properties. For example if AZR site is temporarily available, you can run test with: -Dtest.azr=false
 *
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class JDGUsersTest {

    private TestConfig config;

    private Keycloak awsAdminClient;
    private Keycloak azrAdminClient;
    private Keycloak gceAdminClient;

    private static final String SUMMIT_REALM = "summit";

    private static final String TEST_AWS = "test.aws";
    private static final String TEST_AZR = "test.azr";
    private static final String TEST_GCE = "test.gce";

    private static final String AWS_ROUTE_URL = "aws.route.url";
    private static final String AZR_ROUTE_URL = "azr.route.url";
    private static final String GCE_ROUTE_URL = "gce.route.url";

    private static final String USER_PREFIX = "test";
    private static final String FED_LINK_PREFIX = "google";

    @Before
    public void initClients() throws IOException {
        readConfigFile();

        config.setupDefaults();

        // Use system properties for now. Improve if needed...
        boolean awsEnabled = Boolean.parseBoolean(System.getProperty(TEST_AWS, "true"));
        boolean azrEnabled = Boolean.parseBoolean(System.getProperty(TEST_AZR, "true"));
        boolean gceEnabled = Boolean.parseBoolean(System.getProperty(TEST_GCE, "true"));

        config.setTestAws(awsEnabled);
        config.setTestAzr(azrEnabled);
        config.setTestGce(gceEnabled);

        System.out.println(config);

        if (config.isTestAws()) {
            awsAdminClient = createAdminClient(config.getAwsRouteUrl());
        }

        if (config.isTestAzr()) {
            azrAdminClient = createAdminClient(config.getAzrRouteUrl());
        }

        if (config.isTestGce()) {
            gceAdminClient = createAdminClient(config.getGceRouteUrl());
        }
    }


    private Keycloak createAdminClient(String routeUrl) {
        // TODO: Truststore setup if needed...
        HostnameVerifier hostnameVerifier = new HostnameVerifier() {
            @Override
            public boolean verify(String hostName, SSLSession session) {
                return true;
            }
        };
        ResteasyClient resteasyClient = new ResteasyClientBuilder().disableTrustManager().hostnameVerifier(hostnameVerifier).build();

        KeycloakBuilder builder = KeycloakBuilder.builder()
                .serverUrl(routeUrl + "/auth")
                .realm("master")
                .username("admin")
                .password(config.getAdminPassword())
                .clientId(Constants.ADMIN_CLI_CLIENT_ID)
                .resteasyClient(resteasyClient);

        return builder.build();
    }


    // Read project name and adminPassword from the config file (assumption is that secretstuff was checkout from github)
    private void readConfigFile() throws IOException {
        String userDir = System.getProperty("user.dir");
        if (userDir == null) {
            throw new IllegalStateException("System property user.dir not set. Should point to directory with keycloak-providers module");
        }

        System.out.println("user.dir=" + userDir);

        String filePath = userDir + File.separator + ".." + File.separator + ".." + File.separator + "secretstuff" + File.separator + "sso" + File.separator + "config";
        System.out.println("configFile=" + filePath);

        File configFile = new File(filePath);
        if (!configFile.exists()) {
            throw new IllegalStateException("Config file doesn't exists on specified path. You need to checkout secretstuff repository first");
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(configFile)));

        try {
            String line = "";

            Map<String, String> props = new HashMap<>();
            while (line != null) {
                line = reader.readLine();
                if (line == null) {
                    break;
                }
                if (!line.contains("=")) {
                    continue;
                }

                // 7 is length of "export " - TODO: improve
                String propName = line.substring(7, line.indexOf("=")).trim();
                String propValue = line.substring(line.indexOf("=") + 1).trim();

                // TODO: improve...
                if (propValue.contains("$PROJECT")) {
                    propValue = propValue.replace("$PROJECT", props.get("PROJECT"));
                    propValue = propValue.replace("\"", "");
                    propValue = propValue.replace(";", "");
                }
                props.put(propName, propValue);
            }

            config = new TestConfig();
            config.setSsoProject(props.get("PROJECT"));
            config.setAdminPassword(props.get("ADMIN_PASS"));
            config.setAwsRouteUrl(props.get("AWS_SSO_URL"));
            config.setAzrRouteUrl(props.get("AZR_SSO_URL"));
            config.setGceRouteUrl(props.get("GCE_SSO_URL"));
        } finally {
            reader.close();
        }
    }


    @After
    public void after() {
        if (awsAdminClient != null) {
            awsAdminClient.close();
        }
        if (azrAdminClient != null) {
            azrAdminClient.close();
        }
        if (gceAdminClient != null) {
            gceAdminClient.close();
        }
    }


    @Test
    public void testUsers() {
        // Create test users
        if (config.isTestAws()) {
            createUser(awsAdminClient, USER_PREFIX + "-aws@redhat.com");
        }
        if (config.isTestAzr()) {
            createUser(azrAdminClient, USER_PREFIX + "-azr@redhat.com");
        }
        if (config.isTestGce()) {
            createUser(gceAdminClient, USER_PREFIX + "-gce@redhat.com");
        }

        // Assert created users available everywhere
        if (config.isTestAws()) {
            assertUser(awsAdminClient, "aws", USER_PREFIX + "-aws@redhat.com", null, null, null);

            if (config.isTestAzr()) {
                assertUser(azrAdminClient, "azr", USER_PREFIX + "-aws@redhat.com", null, null, null);
            }
            if (config.isTestGce()) {
                assertUser(gceAdminClient, "gce", USER_PREFIX + "-aws@redhat.com", null, null, null);
            }
        }
        if (config.isTestAzr()) {
            assertUser(azrAdminClient, "azr", USER_PREFIX + "-azr@redhat.com", null, null, null);

            if (config.isTestAws()) {
                assertUser(awsAdminClient, "aws", USER_PREFIX + "-azr@redhat.com", null, null, null);
            }
            if (config.isTestGce()) {
                assertUser(gceAdminClient, "gce", USER_PREFIX + "-azr@redhat.com", null, null, null);
            }
        }
        if (config.isTestGce()) {
            assertUser(gceAdminClient, "gce", USER_PREFIX + "-gce@redhat.com", null, null, null);

            if (config.isTestAws()) {
                assertUser(awsAdminClient, "aws", USER_PREFIX + "-gce@redhat.com", null, null, null);
            }
            if (config.isTestAzr()) {
                assertUser(azrAdminClient, "azr", USER_PREFIX + "-gce@redhat.com", null, null, null);
            }
        }

        // Update users including federationLinks
        if (config.isTestAws()) {
            updateUser(awsAdminClient, USER_PREFIX + "-aws@redhat.com", "Test", "Aws", FED_LINK_PREFIX + "-aws-id", FED_LINK_PREFIX + "-aws-username");
        }
        if (config.isTestAzr()) {
            updateUser(azrAdminClient, USER_PREFIX + "-azr@redhat.com", "Test", "Azr", FED_LINK_PREFIX + "-azr-id", FED_LINK_PREFIX + "-azr-username");
        }
        if (config.isTestGce()) {
            updateUser(gceAdminClient, USER_PREFIX + "-gce@redhat.com", "Test", "Gce", FED_LINK_PREFIX + "-gce-id", FED_LINK_PREFIX + "-gce-username");
        }

        // Assert updated users available everywhere
        if (config.isTestAws()) {
            assertUser(awsAdminClient, "aws",USER_PREFIX + "-aws@redhat.com", "Test", "Aws", FED_LINK_PREFIX + "-aws-id");

            if (config.isTestAzr()) {
                assertUser(azrAdminClient, "azr",USER_PREFIX + "-aws@redhat.com", "Test", "Aws", FED_LINK_PREFIX + "-aws-id");
            }
            if (config.isTestGce()) {
                assertUser(gceAdminClient, "gce",USER_PREFIX + "-aws@redhat.com", "Test", "Aws", FED_LINK_PREFIX + "-aws-id");
            }
        }
        if (config.isTestAzr()) {
            assertUser(azrAdminClient, "azr",USER_PREFIX + "-azr@redhat.com", "Test", "Azr", FED_LINK_PREFIX + "-azr-id");

            if (config.isTestAws()) {
                assertUser(awsAdminClient, "aws", USER_PREFIX + "-azr@redhat.com", "Test", "Azr", FED_LINK_PREFIX + "-azr-id");
            }
            if (config.isTestGce()) {
                assertUser(gceAdminClient, "gce", USER_PREFIX + "-azr@redhat.com", "Test", "Azr", FED_LINK_PREFIX + "-azr-id");
            }
        }
        if (config.isTestGce()) {
            assertUser(gceAdminClient, "gce", USER_PREFIX + "-gce@redhat.com", "Test", "Gce", FED_LINK_PREFIX + "-gce-id");

            if (config.isTestAws()) {
                assertUser(awsAdminClient, "aws", USER_PREFIX + "-gce@redhat.com", "Test", "Gce", FED_LINK_PREFIX + "-gce-id");
            }
            if (config.isTestAzr()) {
                assertUser(azrAdminClient, "azr", USER_PREFIX + "-gce@redhat.com", "Test", "Gce", FED_LINK_PREFIX + "-gce-id");
            }
        }

        // Remove users
        if (config.isTestAws()) {
            removeUser(awsAdminClient, USER_PREFIX + "-aws@redhat.com");
        }
        if (config.isTestAzr()) {
            removeUser(azrAdminClient, USER_PREFIX + "-azr@redhat.com");
        }
        if (config.isTestGce()) {
            removeUser(gceAdminClient, USER_PREFIX + "-gce@redhat.com");
        }

        // Assert users removed everywhere
        if (config.isTestAws()) {
            Assert.assertNull(searchUserByEmail(awsAdminClient, USER_PREFIX + "-aws@redhat.com"));

            if (config.isTestAzr()) {
                Assert.assertNull(searchUserByEmail(azrAdminClient, USER_PREFIX + "-aws@redhat.com"));
            }
            if (config.isTestGce()) {
                Assert.assertNull(searchUserByEmail(gceAdminClient, USER_PREFIX + "-aws@redhat.com"));
            }
        }
        if (config.isTestAzr()) {
            Assert.assertNull(searchUserByEmail(azrAdminClient, USER_PREFIX + "-azr@redhat.com"));

            if (config.isTestAws()) {
                Assert.assertNull(searchUserByEmail(awsAdminClient, USER_PREFIX + "-azr@redhat.com"));
            }
            if (config.isTestGce()) {
                Assert.assertNull(searchUserByEmail(gceAdminClient, USER_PREFIX + "-azr@redhat.com"));
            }
        }
        if (config.isTestGce()) {
            Assert.assertNull(searchUserByEmail(gceAdminClient, USER_PREFIX + "-gce@redhat.com"));

            if (config.isTestAws()) {
                Assert.assertNull(searchUserByEmail(awsAdminClient, USER_PREFIX + "-gce@redhat.com"));
            }
            if (config.isTestAzr()) {
                Assert.assertNull(searchUserByEmail(azrAdminClient, USER_PREFIX + "-gce@redhat.com"));
            }
        }
    }

    private static void createUser(Keycloak adminClient, String email) {
        UserRepresentation user = searchUserByEmail(adminClient, email);
        if (user != null) {
            adminClient.realm(SUMMIT_REALM).users().get(user.getId()).remove();
        }

        user = new UserRepresentation();
        user.setUsername(email);
        user.setEmail(email);
        user.setEnabled(true);

        Response response = adminClient.realm(SUMMIT_REALM).users().create(user);
        String id = getCreatedId(response);
        Assert.assertTrue(id.startsWith("f:") && id.endsWith(":" + email));
        response.close();

        System.out.println("Created user: " + email);
    }

    private static void updateUser(Keycloak adminClient, String email, String firstName, String lastName, String fedId, String fedUsername) {
        UserRepresentation user = searchUserByEmail(adminClient, email);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        UserResource userRes = adminClient.realm(SUMMIT_REALM).users().get(user.getId());
        userRes.update(user);

        FederatedIdentityRepresentation googleLink = new FederatedIdentityRepresentation();
        googleLink.setIdentityProvider("google");
        googleLink.setUserId(fedId);
        googleLink.setUserName(fedUsername);
        Response response = userRes.addFederatedIdentity("google", googleLink);
        Assert.assertEquals(204, response.getStatus());
        response.close();

        System.out.println("Updated user: " + email);
    }

    private static void removeUser(Keycloak adminClient, String email) {
        UserRepresentation user = searchUserByEmail(adminClient, email);
        adminClient.realm(SUMMIT_REALM).users().get(user.getId()).remove();
        System.out.println("Removed user: " + email);
    }

    private static UserRepresentation searchUserByEmail(Keycloak adminClient, String email) {
        List<UserRepresentation> users = adminClient.realm(SUMMIT_REALM).users().search(email, 0, 1);
        if (users.size() == 0) {
            return null;
        } else {
            return users.get(0);
        }
    }

    private static void assertUser(Keycloak adminClient, String siteName, String email, String firstName, String lastName, String fedId) {
        UserRepresentation user = searchUserByEmail(adminClient, email);
        Assert.assertNotNull(user);
        Assert.assertEquals(email, user.getUsername());
        Assert.assertEquals(email, user.getEmail());
        Assert.assertEquals(firstName, user.getFirstName());
        Assert.assertEquals(lastName, user.getLastName());

        List<FederatedIdentityRepresentation> socialLinks = adminClient.realm(SUMMIT_REALM).users().get(user.getId()).getFederatedIdentity();
        if (fedId == null) {
            Assert.assertEquals(0, socialLinks.size());
        } else {
            Assert.assertEquals(1, socialLinks.size());
            FederatedIdentityRepresentation link = socialLinks.get(0);
            Assert.assertEquals("google", link.getIdentityProvider());
            Assert.assertEquals(fedId, link.getUserId());
        }

        System.out.println("Tested user: " + email + " on " + siteName);
    }


    // ApiUtil.getCreatedId
    private static String getCreatedId(Response response) {
        URI location = response.getLocation();
        if (!response.getStatusInfo().equals(Response.Status.CREATED)) {
            Response.StatusType statusInfo = response.getStatusInfo();
            throw new WebApplicationException("Create method returned status "
                    + statusInfo.getReasonPhrase() + " (Code: " + statusInfo.getStatusCode() + "); expected status: Created (201)", response);
        }
        if (location == null) {
            return null;
        }
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

}
