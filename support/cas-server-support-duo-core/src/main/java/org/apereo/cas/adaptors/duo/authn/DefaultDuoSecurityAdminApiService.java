package org.apereo.cas.adaptors.duo.authn;

import org.apereo.cas.adaptors.duo.DuoSecurityBypassCode;
import org.apereo.cas.adaptors.duo.DuoSecurityUserAccount;
import org.apereo.cas.adaptors.duo.DuoSecurityUserAccountStatus;
import org.apereo.cas.adaptors.duo.DuoSecurityUserDevice;
import org.apereo.cas.configuration.model.support.mfa.DuoSecurityMultifactorAuthenticationProperties;
import org.apereo.cas.util.CollectionUtils;
import org.apereo.cas.util.function.FunctionUtils;
import org.apereo.cas.util.http.HttpClient;

import com.duosecurity.client.Http;
import com.squareup.okhttp.OkHttpClient;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.http.HttpMethod;
import org.springframework.util.ReflectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * This is {@link DefaultDuoSecurityAdminApiService}.
 *
 * @author Misagh Moayyed
 * @since 6.4.0
 */
@Slf4j
@EqualsAndHashCode
@RequiredArgsConstructor
public class DefaultDuoSecurityAdminApiService implements DuoSecurityAdminApiService {
    private final HttpClient httpClient;

    private final DuoSecurityMultifactorAuthenticationProperties duoProperties;

    private static String getAdminEndpointUri(final String uri) {
        return "/admin/v1/" + uri;
    }

    private static DuoSecurityUserAccount mapDuoSecurityUserAccount(final JSONObject userJson) throws JSONException {
        val user = new DuoSecurityUserAccount(userJson.getString("username"));
        user.setStatus(DuoSecurityUserAccountStatus.from(userJson.getString("status")));
        FunctionUtils.doIfNotNull(userJson.getString("email"), value -> user.addAttribute("email", value));
        FunctionUtils.doIfNotNull(userJson.getString("user_id"), value -> user.addAttribute("user_id", value));
        FunctionUtils.doIfNotNull(userJson.getString("firstname"), value -> user.addAttribute("firstname", value));
        FunctionUtils.doIfNotNull(userJson.getString("lastname"), value -> user.addAttribute("lastname", value));
        FunctionUtils.doIfNotNull(userJson.getString("realname"), value -> user.addAttribute("realname", value));
        FunctionUtils.doIfNotNull(userJson.getBoolean("is_enrolled"), value -> user.addAttribute("is_enrolled", value.toString()));
        FunctionUtils.doIfNotNull(userJson.getLong("last_login"), value -> user.addAttribute("last_login", value.toString()));
        FunctionUtils.doIfNotNull(userJson.getLong("created"), value -> user.addAttribute("created", value.toString()));
        if (user.getStatus() != DuoSecurityUserAccountStatus.DENY && !user.isEnrolled()) {
            user.setStatus(DuoSecurityUserAccountStatus.ENROLL);
        }
        val phones = userJson.getJSONArray("phones");
        for (int i = 0; phones != null && i < phones.length(); i++) {
            val phoneJson = phones.getJSONObject(i);
            val phone = new DuoSecurityUserDevice(phoneJson.getString("name"), phoneJson.getString("type"));
            phone.setActivated(phoneJson.optBoolean("activated"));
            phone.setLastSeen(phoneJson.optString("last_seen"));
            phone.setModel(phoneJson.optString("model"));
            phone.setNumber(phoneJson.optString("number"));
            phone.setPlatform(phoneJson.optString("platform"));
            phone.setId(phoneJson.optString("phone_id"));
            phone.setCapabilities(List.of(phoneJson.getJSONArray("capabilities")
                .join(",")
                .replace("\"", StringUtils.EMPTY)
                .split(",")));
            user.addDevice(phone);
        }
        return user;
    }

    @Override
    public Optional<DuoSecurityUserAccount> getDuoSecurityUserAccount(final String username) throws Exception {
        val userResponse = getEndpointResultFor(CollectionUtils.wrap("username", username));
        if (userResponse != null && userResponse.length() == 1) {
            val userJson = userResponse.getJSONObject(0);
            val user = mapDuoSecurityUserAccount(userJson);
            user.addBypassCodes(getDuoSecurityBypassCodesFor(user.getUserId()));
            return Optional.of(user);
        }
        return Optional.empty();
    }

    @Override
    public List<DuoSecurityBypassCode> getDuoSecurityBypassCodesFor(final String userIdentifier) throws Exception {
        val codes = new ArrayList<DuoSecurityBypassCode>();
        val bypassResponse = getEndpointResultFor(CollectionUtils.wrap("uri", String.format("%s/bypass_codes", userIdentifier)));
        for (int i = 0; bypassResponse != null && i < bypassResponse.length(); i++) {
            val bypassJson = bypassResponse.getJSONObject(i);
            if (bypassJson.has("bypass_code_id")) {
                val code = new DuoSecurityBypassCode(bypassJson.getString("bypass_code_id"));
                code.setCreated(bypassJson.optLong("created"));
                code.setExpiration(bypassJson.optLong("expiration"));
                code.setReuseCount(bypassJson.optLong("reuse_count"));
                code.setCreatedBy(bypassJson.optString("admin_email"));
                codes.add(code);
            }
        }
        return codes;
    }

    private JSONArray getEndpointResultFor(final Map<String, String> params) throws Exception {
        val uri = getAdminEndpointUri(params.getOrDefault("uri", "users"));
        val request = new Http(HttpMethod.GET.name(), duoProperties.getDuoApiHost(), uri);
        request.addParam("offset", "0");
        request.addParam("limit", "1");
        params.forEach(request::addParam);
        request.signRequest(duoProperties.getDuoAdminIntegrationKey(), duoProperties.getDuoAdminSecretKey());
        val factory = this.httpClient.getHttpClientFactory();
        Optional.ofNullable(factory.getProxy()).ifPresent(proxy -> request.setProxy(proxy.getHostName(), proxy.getPort()));
        val field = ReflectionUtils.findField(request.getClass(), "httpClient");
        ReflectionUtils.makeAccessible(Objects.requireNonNull(field));
        val client = Objects.requireNonNull((OkHttpClient) ReflectionUtils.getField(field, request));
        client.setHostnameVerifier(factory.getHostnameVerifier());
        client.setSslSocketFactory(factory.getSslContext().getSocketFactory());
        val result = (JSONObject) request.executeJSONRequest();
        return result.length() > 0 && result.has("response") && result.has("stat")
            ? result.getJSONArray("response")
            : null;
    }
}