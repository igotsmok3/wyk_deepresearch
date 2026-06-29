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
 * JSON 序列化/反序列化工具类，基于 Jackson ObjectMapper 提供对象与 JSON 字符串的相互转换。
 *
 * <p>项目职责：封装全局统一配置的 ObjectMapper（忽略未知属性、不序列化空 Bean、
 * 非 null 字段才序列化、注册 Jdk8Module / JavaTimeModule），简化各模块的 JSON 处理代码。
 *
 * <p>被使用情况：{@code TemplateUtil} 使用本类将短期记忆对象序列化为 JSON 注入 Prompt 模板；
 * {@code ShortUserRoleMemoryNode} 使用本类序列化/反序列化用户角色画像；
 * 其他工具类和节点按需调用。
 *
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
