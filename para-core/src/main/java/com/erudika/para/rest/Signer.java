/*
 * Copyright 2013-2021 Erudika. https://erudika.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * For issues and patches go to: https://github.com/erudika
 */
package com.erudika.para.rest;

import com.erudika.para.utils.Config;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.ws.rs.core.MultivaluedMap;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.signer.internal.BaseAws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

/**
 * This class extends {@code BaseAws4Signer} implementing the AWS Signature Version 4 algorithm.
 * Also contains a method for signature validation. The signatures that this class produces are
 * compatible with the original AWS SDK implementation.
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public final class Signer extends BaseAws4Signer {

	private static final Logger logger = LoggerFactory.getLogger(Signer.class);
	private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.
			ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneId.of("Z"));

	/**
	 * No-args constructor.
	 */
	public Signer() { }

	/**
	 * Signs a request using AWS signature V4.
	 * @param httpeMethod GET/POST/PUT... etc.
	 * @param endpoint the hostname of the API server
	 * @param resourcePath the path of the resource (starting from root e.g. "/path/to/res")
	 * @param headers the headers map
	 * @param params the params map
	 * @param entity the entity object or null
	 * @param accessKey the app's access key
	 * @param secretKey the app's secret key
	 * @return a signed request. The actual signature is inside the {@code Authorization} header.
	 */
	public Map<String, String> sign(String httpeMethod, String endpoint, String resourcePath,
			Map<String, String> headers, Map<String, String> params, InputStream entity,
			String accessKey, String secretKey) {

		String date = Optional.ofNullable(headers).orElse(Collections.emptyMap()).get("x-amz-date");
		Clock override = date != null ? Clock.fixed(parseAWSInstant(date), ZoneOffset.UTC) : null;
		SdkHttpFullRequest req = buildAWSRequest(httpeMethod, endpoint, resourcePath, headers, params, entity);
		req = sign(req, accessKey, secretKey, override);
		Map<String, String> headerz = new HashMap<>(req.headers().size());
		for (String header : req.headers().keySet()) {
			headerz.put(header, req.firstMatchingHeader(header).orElse(""));
		}
		return headerz;
	}

	/**
	 * Signs a request using AWS signature V4.
	 * @param request the request instance
	 * @param accessKey the app's access key
	 * @param secretKey the app's secret key
	 * @param override the clock override from x-amz-date
	 * @return the request object
	 */
	public SdkHttpFullRequest sign(SdkHttpFullRequest request, String accessKey, String secretKey, Clock override) {
		Aws4SignerParams.Builder<?> signerParams = Aws4SignerParams.builder().
				awsCredentials(AwsBasicCredentials.create(accessKey, secretKey)).
				// this is important!
				doubleUrlEncode(true).
				signingName(Config.PARA).
				signingRegion(Region.US_EAST_1);
		if (override != null) {
			signerParams.signingClockOverride(override);
		}
		return super.sign(request, signerParams.build());
	}

	private SdkHttpFullRequest buildAWSRequest(String httpMethod, String endpoint, String resourcePath,
			Map<String, String> headers, Map<String, String> params, InputStream entity) {
		SdkHttpFullRequest.Builder r = SdkHttpFullRequest.builder();

		if (!StringUtils.isBlank(httpMethod)) {
			r.method(SdkHttpMethod.valueOf(httpMethod));
		}

		if (!StringUtils.isBlank(endpoint)) {
			if (endpoint.startsWith("https://")) {
				r.protocol("HTTPS");
				r.host(StringUtils.removeStart(endpoint, "https://"));
			} else if (endpoint.startsWith("http://")) {
				r.protocol("HTTP");
				r.host(StringUtils.removeStart(endpoint, "http://"));
			}
		}
		if (!StringUtils.isBlank(resourcePath)) {
			// Don't encode resource paths manually! can lead to invalid signatures
			r.encodedPath(resourcePath);
			//r.encodedPath(SdkHttpUtils.urlEncodeIgnoreSlashes(resourcePath));
		}

		if (headers != null) {
			// we don't need these here, added by default
			headers.remove("host");
			headers.remove("x-amz-date");
			headers.entrySet().forEach(e -> r.putHeader(e.getKey(), e.getValue()));
		}
		if (params != null) {
			params.entrySet().forEach(e -> r.appendRawQueryParameter(e.getKey(), e.getValue()));
		}
		if (entity != null) {
			r.contentStreamProvider(() -> entity);
		}
		return r.build();
	}

	/**
	 * Returns a parsed Date.
	 * @param date a date in the AWS format yyyyMMdd'T'HHmmss'Z'
	 * @return a date
	 */
	public static Date parseAWSDate(String date) {
		if (StringUtils.isBlank(date)) {
			return null;
		}
		return Date.from(parseAWSInstant(date));
	}

	/**
	 * Returns a parsed Instant.
	 * @param date a date in the AWS format yyyyMMdd'T'HHmmss'Z'
	 * @return a date
	 */
	public static Instant parseAWSInstant(String date) {
		if (StringUtils.isBlank(date)) {
			return null;
		}
		return LocalDateTime.from(TIME_FORMATTER.parse(date)).toInstant(ZoneOffset.UTC);
	}

	/**
	 * Builds and signs a request to an API endpoint using the provided credentials.
	 * @param accessKey access key
	 * @param secretKey secret key
	 * @param httpMethod the method (GET, POST...)
	 * @param endpointURL protocol://host:port
	 * @param reqPath the API resource path relative to the endpointURL
	 * @param headers headers map
	 * @param params parameters map
	 * @param jsonEntity an object serialized to JSON byte array (payload), could be null
	 * @return a map containing the "Authorization" header
	 */
	public Map<String, String> signRequest(String accessKey, String secretKey,
			String httpMethod, String endpointURL, String reqPath,
			Map<String, String> headers, MultivaluedMap<String, String> params, byte[] jsonEntity) {
		if (headers == null) {
			headers = new HashMap<>();
		}
		if (StringUtils.isBlank(accessKey)) {
			logger.error("Blank access key: {} {}", httpMethod, reqPath);
			return headers;
		}

		if (StringUtils.isBlank(secretKey)) {
			logger.debug("Anonymous request: {} {}", httpMethod, reqPath);
			headers.put("Authorization", "Anonymous " + accessKey);
			return headers;
		}

		if (httpMethod == null) {
			httpMethod = "GET";
		}

		InputStream in = null;
		Map<String, String> sigParams = new HashMap<>();

		if (params != null) {
			for (Map.Entry<String, List<String>> param : params.entrySet()) {
				String key = param.getKey();
				List<String> value = param.getValue();
				if (value != null && !value.isEmpty() && value.get(0) != null) {
					sigParams.put(key, value.get(0));
				}
			}
		}

		if (jsonEntity != null && jsonEntity.length > 0) {
			in = new ByteArrayInputStream(jsonEntity);
		}

		return sign(httpMethod, endpointURL, reqPath, headers, sigParams, in, accessKey, secretKey);
	}
}
