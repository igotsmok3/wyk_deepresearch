package com.alibaba.cloud.ai.example.deepresearch.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.text.SimpleDateFormat;

/**
 * @author benym
 */
public class JsonUtil {

	private static final Logger LOGGER = LoggerFactory.getLogger(JsonUtil.class);

	private static final ObjectMapper OM = new ObjectMapper();

	static {
		OM.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
		OM.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
		OM.setDateFormat(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));
		SimpleModule module = new SimpleModule("money", Version.unknownVersion());
		OM.registerModule(module);
		OM.registerModule(new Jdk8Module());
		OM.registerModule(new JavaTimeModule());
		OM.setSerializationInclusion(JsonInclude.Include.NON_NULL);
	}

	public static String toJson(Object obj) {
		if (obj == null) {
			return "";
		}
		try {
			return OM.writeValueAsString(obj);
		}
		catch (Exception e) {
			LOGGER.error("JsonUtil toJson error", e);
		}
		return "";
	}

	public static <T> T fromJson(String json, Class<T> clazz) {
		if (!StringUtils.hasText(json)) {
			return null;
		}
		try {
			return OM.readValue(json, clazz);
		}
		catch (Exception e) {
			LOGGER.error("JsonUtil fromJson error", e);
		}
		return null;
	}

	public static <T> T fromJson(String json, TypeReference<T> typeReference) {
		if (!StringUtils.hasText(json)) {
			return null;
		}
		try {
			return OM.readValue(json, typeReference);
		}
		catch (Exception e) {
			LOGGER.error("JsonUtil fromJson error", e);
		}
		return null;
	}

}
