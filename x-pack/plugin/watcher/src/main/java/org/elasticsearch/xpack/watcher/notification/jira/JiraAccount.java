/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.notification.jira;

import org.elasticsearch.common.Booleans;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.common.settings.SecureSetting;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsException;
import org.elasticsearch.common.xcontent.LoggingDeprecationHandler;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.xpack.watcher.common.http.HttpClient;
import org.elasticsearch.xpack.watcher.common.http.HttpMethod;
import org.elasticsearch.xpack.watcher.common.http.HttpProxy;
import org.elasticsearch.xpack.watcher.common.http.HttpRequest;
import org.elasticsearch.xpack.watcher.common.http.HttpResponse;
import org.elasticsearch.xpack.watcher.common.http.Scheme;
import org.elasticsearch.xpack.watcher.common.http.auth.basic.BasicAuth;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Map;

public class JiraAccount {

    /**
     * Default JIRA REST API path for create issues
     **/
    public static final String DEFAULT_PATH = "/rest/api/2/issue";

    static final String USER_SETTING = "user";
    static final String PASSWORD_SETTING = "password";
    static final String URL_SETTING = "url";
    static final String ISSUE_DEFAULTS_SETTING = "issue_defaults";
    static final String ALLOW_HTTP_SETTING = "allow_http";

    private static final Setting<SecureString> SECURE_USER_SETTING = SecureSetting.secureString("secure_" + USER_SETTING, null);
    private static final Setting<SecureString> SECURE_PASSWORD_SETTING = SecureSetting.secureString("secure_" + PASSWORD_SETTING, null);
    private static final Setting<SecureString> SECURE_URL_SETTING = SecureSetting.secureString("secure_" + URL_SETTING, null);

    private final HttpClient httpClient;
    private final String name;
    private final String user;
    private final String password;
    private final URI url;
    private final Map<String, Object> issueDefaults;

    public JiraAccount(String name, Settings settings, HttpClient httpClient) {
        this.httpClient = httpClient;
        this.name = name;
        String url = getSetting(name, URL_SETTING, settings, SECURE_URL_SETTING);
        ESLoggerFactory.getLogger(getClass()).error("THE URL WAS [{}]", url);
        try {
            URI uri = new URI(url);
            Scheme protocol = Scheme.parse(uri.getScheme());
            if ((protocol == Scheme.HTTP) && (Booleans.isTrue(settings.get(ALLOW_HTTP_SETTING)) == false)) {
                throw new SettingsException("invalid jira [" + name + "] account settings. unsecure scheme [" + protocol + "]");
            }
            this.url = uri;
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new SettingsException("invalid jira [" + name + "] account settings. invalid [" + URL_SETTING + "] setting", e);
        }
        this.user = getSetting(name, USER_SETTING, settings, SECURE_USER_SETTING);
        if (Strings.isEmpty(this.user)) {
            throw requiredSettingException(name, USER_SETTING);
        }
        this.password = getSetting(name, PASSWORD_SETTING, settings, SECURE_PASSWORD_SETTING);
        if (Strings.isEmpty(this.password)) {
            throw requiredSettingException(name, PASSWORD_SETTING);
        }
        try (XContentBuilder builder = XContentBuilder.builder(XContentType.JSON.xContent())) {
            builder.startObject();
            settings.getAsSettings(ISSUE_DEFAULTS_SETTING).toXContent(builder, ToXContent.EMPTY_PARAMS);
            builder.endObject();
            try (InputStream stream = BytesReference.bytes(builder).streamInput();
                 XContentParser parser = XContentType.JSON.xContent()
                         .createParser(new NamedXContentRegistry(Collections.emptyList()), LoggingDeprecationHandler.INSTANCE, stream)) {
                this.issueDefaults = Collections.unmodifiableMap(parser.map());
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String getSetting(String accountName, String settingName, Settings settings, Setting<SecureString> secureSetting) {
        String value = settings.get(settingName);
        if (value == null) {
            SecureString secureString = secureSetting.get(settings);
            if (secureString == null || secureString.length() < 1) {
                throw requiredSettingException(accountName, settingName);
            }
            value = secureString.toString();
        }

        return value;
    }

    public String getName() {
        return name;
    }

    public Map<String, Object> getDefaults() {
        return issueDefaults;
    }

    public JiraIssue createIssue(final Map<String, Object> fields, final HttpProxy proxy) throws IOException {
        HttpRequest request = HttpRequest.builder(url.getHost(), url.getPort())
                .scheme(Scheme.parse(url.getScheme()))
                .method(HttpMethod.POST)
                .path(url.getPath().isEmpty() || url.getPath().equals("/") ? DEFAULT_PATH : url.getPath())
                .jsonBody((builder, params) -> builder.field("fields", fields))
                .auth(new BasicAuth(user, password.toCharArray()))
                .proxy(proxy)
                .build();

        HttpResponse response = httpClient.execute(request);
        return JiraIssue.responded(name, fields, request, response);
    }

    private static SettingsException requiredSettingException(String account, String setting) {
        return new SettingsException("invalid jira [" + account + "] account settings. missing required [" + setting + "] setting");
    }
}
