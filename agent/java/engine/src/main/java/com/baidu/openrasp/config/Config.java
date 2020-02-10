/*
 * Copyright 2017-2019 Baidu Inc.
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

package com.baidu.openrasp.config;

import com.baidu.openrasp.cloud.model.HookWhiteModel;
import com.baidu.openrasp.cloud.syslog.DynamicConfigAppender;
import com.baidu.openrasp.cloud.utils.CloudUtils;
import com.baidu.openrasp.exceptions.ConfigLoadException;
import com.baidu.openrasp.messaging.ErrorType;
import com.baidu.openrasp.messaging.LogConfig;
import com.baidu.openrasp.messaging.LogTool;
import com.baidu.openrasp.plugin.checker.CheckParameter;
import com.baidu.openrasp.plugin.checker.local.ConfigurableChecker;
import com.baidu.openrasp.tool.FileUtil;
import com.baidu.openrasp.tool.LRUCache;
import com.baidu.openrasp.tool.Reflection;
import com.baidu.openrasp.tool.cpumonitor.CpuMonitorManager;
import com.baidu.openrasp.tool.filemonitor.FileScanListener;
import com.baidu.openrasp.tool.filemonitor.FileScanMonitor;
import com.fuxi.javaagent.contentobjects.jnotify.JNotifyException;
import com.google.gson.*;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.*;


/**
 * Created by tyy on 3/27/17.
 * 项目配置类，通过解析conf/rasp.property文件来加载配置
 * 若没有找到配置文件使用默认值
 */
public class Config extends FileScanListener {

    public enum Item {
        PLUGIN_TIMEOUT_MILLIS("plugin.timeout.millis", "100"),
        HOOKS_IGNORE("hooks.ignore", ""),
        INJECT_URL_PREFIX("inject.urlprefix", ""),
        REQUEST_PARAM_ENCODING("request.param_encoding", ""),
        BODY_MAX_BYTES("body.maxbytes", "12288"),
        LOG_MAX_BACKUP("log.maxbackup", "30"),
        PLUGIN_MAX_STACK("plugin.maxstack", "100"),
        SQL_CACHE_CAPACITY("lru.max_size", "1024"),
        PLUGIN_FILTER("plugin.filter", "true"),
        OGNL_EXPRESSION_MIN_LENGTH("ognl.expression.minlength", "30"),
        SQL_SLOW_QUERY_MIN_ROWS("sql.slowquery.min_rows", "500"),
        BLOCK_STATUS_CODE("block.status_code", "302"),
        DEBUG("debug.level", "0"),
        ALGORITHM_CONFIG("algorithm.config", "{}", false),
        CLIENT_IP_HEADER("clientip.header", "ClientIP"),
        BLOCK_REDIRECT_URL("block.redirect_url", "https://rasp.baidu.com/blocked/?request_id=%request_id%"),
        BLOCK_JSON("block.content_json", "{\"error\":true, \"reason\": \"Request blocked by OpenRASP\", \"request_id\": \"%request_id%\"}"),
        BLOCK_XML("block.content_xml", "<?xml version=\"1.0\"?><doc><error>true</error><reason>Request blocked by OpenRASP</reason><request_id>%request_id%</request_id></doc>"),
        BLOCK_HTML("block.content_html", "</script><script>location.href=\"https://rasp.baidu.com/blocked2/?request_id=%request_id%\"</script>"),
        CLOUD_SWITCH("cloud.enable", "false"),
        CLOUD_ADDRESS("cloud.backend_url", ""),
        CLOUD_APPID("cloud.app_id", ""),
        CLOUD_APPSECRET("cloud.app_secret", ""),
        RASP_ID("rasp.id", ""),
        SYSLOG_ENABLE("syslog.enable", "false"),
        SYSLOG_URL("syslog.url", ""),
        SYSLOG_TAG("syslog.tag", "OPENRASP"),
        SYSLOG_FACILITY("syslog.facility", "1"),
        SYSLOG_RECONNECT_INTERVAL("syslog.reconnect_interval", "300000"),
        LOG_MAXBURST("log.maxburst", "100"),
        HEARTBEAT_INTERVAL("cloud.heartbeat_interval", "90"),
        HOOK_WHITE("hook.white", ""),
        HOOK_WHITE_ALL("hook.white.ALL", "true"),
        DECOMPILE_ENABLE("decompile.enable", "false"),
        RESPONSE_HEADERS("inject.custom_headers", ""),
        CPU_USAGE_PERCENT("cpu.usage.percent", "90"),
        CPU_USAGE_ENABLE("cpu.usage.enable", "false"),
        CPU_USAGE_INTERVAL("cpu.usage.interval", "5"),
        HTTPS_VERIFY_SSL("openrasp.ssl_verifypeer", "false"),
        LRU_COMPARE_ENABLE("lru.compare_enable", "false"),
        LRU_COMPARE_LIMIT("lru.compare_limit", "10240");


        Item(String key, String defaultValue) {
            this(key, defaultValue, true);
        }

        Item(String key, String defaultValue, boolean isProperties) {
            this.key = key;
            this.defaultValue = defaultValue;
            this.isProperties = isProperties;
        }

        String key;
        String defaultValue;
        boolean isProperties;

        @Override
        public String toString() {
            return key;
        }
    }

    private static final int MAX_SQL_EXCEPTION_CODES_CONUT = 100;
    private static final String HOOKS_WHITE = "hook.white";
    private static final String RESPONSE_HEADERS = "inject.custom_headers";
    private static final String CONFIG_DIR_NAME = "conf";
    private static final String CONFIG_FILE_NAME = "openrasp.yml";
    public static final int REFLECTION_STACK_START_INDEX = 0;
    public static final Logger LOGGER = Logger.getLogger(Config.class.getName());
    public static String baseDirectory;
    private static Integer watchId;
    //全局lru的缓存
    public static LRUCache<Object, String> commonLRUCache;

    private String configFileDir;
    private int pluginMaxStack;
    private long pluginTimeout;
    private int bodyMaxBytes;
    private int sqlSlowQueryMinCount;
    private String[] ignoreHooks;
    private String[] reflectionMonitorMethod;
    private int logMaxStackSize;
    private String blockUrl;
    private String injectUrlPrefix;
    private String requestParamEncoding;
    private int ognlMinLength;
    private int blockStatusCode;
    private int debugLevel;
    private JsonObject algorithmConfig;
    private String blockJson;
    private String blockXml;
    private String blockHtml;
    private boolean pluginFilter;
    private String clientIp;
    private boolean cloudSwitch;
    private String cloudAddress;
    private String cloudAppId;
    private String cloudAppSecret;
    private int sqlCacheCapacity;
    private boolean syslogSwitch;
    private String syslogUrl;
    private String syslogTag;
    private int syslogReconnectInterval;
    private boolean hookWhiteAll;
    private int logMaxBurst;
    private int heartbeatInterval;
    private int syslogFacility;
    private boolean decompileEnable;
    private Map<Object, Object> responseHeaders;
    private int logMaxBackUp;
    private boolean disableHooks;
    private boolean cpuUsageEnable;
    private int cpuUsagePercent;
    private int cpuUsageCheckInterval;
    private boolean isHttpsVerifyPeer;
    private String raspId;
    private HashSet<Integer> sqlErrorCodes = new HashSet<Integer>();
    private boolean lruCompareEnable;
    private int lruCompareLimit;


    static {
        baseDirectory = FileUtil.getBaseDir();
        if (!getConfig().getCloudSwitch()) {
            CustomResponseHtml.load(baseDirectory);
        }
        //初始化全局缓存
        commonLRUCache = new LRUCache<Object, String>(getConfig().getSqlCacheCapacity());
        LOGGER.info("baseDirectory: " + baseDirectory);
    }

    /**
     * 构造函数，初始化全局配置
     */
    private Config() {
        this.configFileDir = baseDirectory + File.separator + CONFIG_DIR_NAME;
        String configFilePath = this.configFileDir + File.separator + CONFIG_FILE_NAME;
        try {
            loadConfigFromFile(new File(configFilePath), true);
            if (!getCloudSwitch()) {
                try {
                    FileScanMonitor.addMonitor(
                            baseDirectory, ConfigHolder.instance);
                } catch (JNotifyException e) {
                    throw new ConfigLoadException("add listener on " + baseDirectory + " failed because:" + e.getMessage());
                }
                addConfigFileMonitor();
            }
        } catch (FileNotFoundException e) {
            handleException("Could not find openrasp.yml, using default settings: " + e.getMessage(), e);
        } catch (JNotifyException e) {
            handleException("add listener on " + configFileDir + " failed because:" + e.getMessage(), e);
        } catch (Exception e) {
            handleException("cannot load properties file: " + e.getMessage(), e);
        }
        String configValidMsg = checkMajorConfig();
        if (configValidMsg != null) {
            LogTool.error(ErrorType.CONFIG_ERROR, configValidMsg);
            throw new ConfigLoadException(configValidMsg);
        }
    }

    /**
     * 检查关键配置选项
     */
    private String checkMajorConfig() {
        if (!StringUtils.isEmpty(raspId)) {
            if ((raspId.length() < 16 || raspId.length() > 512)) {
                return "the length of rasp.id must be between [16,512]";
            }
            for (int i = 0; i < raspId.length(); i++) {
                char a = raspId.charAt(i);
                if (!((a >= 'a' && a <= 'z') || (a >= '0' && a <= '9') || (a >= 'A' && a <= 'Z'))) {
                    return "the rasp.id can only contain letters and numbers";
                }
            }
        }
        return null;
    }

    @SuppressWarnings({"unchecked"})
    private synchronized void loadConfigFromFile(File file, boolean isInit) throws Exception {
        Map<String, Object> properties = null;
        try {
            if (file.exists()) {
                Yaml yaml = new Yaml();
                properties = yaml.loadAs(new FileInputStream(file), Map.class);
            }
        } catch (Exception e) {
            LogTool.warn(ErrorType.CONFIG_ERROR, "openrasp.yml parsing failed: " + e.getMessage(), e);
        } finally {
            TreeMap<String, Integer> temp = new TreeMap<String, Integer>();
            // 出现解析问题使用默认值
            for (Config.Item item : Config.Item.values()) {
                if (item.key.equals(HOOKS_WHITE)) {
                    if (properties != null) {
                        Object object = properties.get(item.key);
                        if (object instanceof Map) {
                            Map<Object, Object> hooks = (Map<Object, Object>) object;
                            temp.putAll(parseHookWhite(hooks));
                        }
                    }
                    setHooksWhite(temp);
                    continue;
                }
                if (item.key.equals(RESPONSE_HEADERS)) {
                    if (properties != null) {
                        Object object = properties.get(item.key);
                        if (object instanceof Map) {
                            Map<Object, Object> headers = (Map<Object, Object>) object;
                            setResponseHeaders(headers);
                        }
                    }
                    continue;
                }
                if (item.isProperties) {
                    setConfigFromProperties(item, properties, isInit);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    public synchronized void loadConfigFromCloud(Map<String, Object> configMap, boolean isInit) throws Exception {
        TreeMap<String, Integer> temp = new TreeMap<String, Integer>();
        for (Map.Entry<String, Object> entry : configMap.entrySet()) {
            //开启云控必须参数不能云控
            if (entry.getKey().startsWith("cloud.") || entry.getKey().equals("rasp.id")) {
                continue;
            }

            if (entry.getKey().equals(HOOKS_WHITE)) {
                if (entry.getValue() instanceof JsonObject) {
                    Map<Object, Object> hooks = CloudUtils.getMapGsonObject().fromJson((JsonObject) entry.getValue(), Map.class);
                    temp.putAll(parseHookWhite(hooks));
                }
            } else if (entry.getKey().equals(RESPONSE_HEADERS)) {
                if (entry.getValue() instanceof JsonObject) {
                    Map<Object, Object> headers = CloudUtils.getMapGsonObject().fromJson((JsonObject) entry.getValue(), Map.class);
                    setResponseHeaders(headers);
                }
            } else {
                try {
                    if (entry.getValue() instanceof JsonPrimitive) {
                        setConfig(entry.getKey(), ((JsonPrimitive) entry.getValue()).getAsString(), isInit);
                    }
                } catch (Exception e) {
                    // 出现解析问题使用默认值
                    for (Config.Item item : Config.Item.values()) {
                        if (item.key.equals(entry.getKey())) {
                            setConfig(item.key, item.defaultValue, isInit);
                            String message = "set config " + entry.getKey() + " from cloud failed with value "
                                    + entry.getValue() + ", use default value : " + item.defaultValue;
                            LogTool.warn(ErrorType.CONFIG_ERROR, message + ", because: " + e.getMessage(), e);
                        }
                    }
                }
            }

        }
        setHooksWhite(temp);
    }

    private void reloadConfig(File file) {
        if (file.getName().equals(CONFIG_FILE_NAME)) {
            try {
                loadConfigFromFile(file, false);
                //单机模式下动态添加获取删除syslog和动态更新syslog tag
                if (!CloudUtils.checkCloudControlEnter()) {
                    //关闭或者打开syslog服务
                    LogConfig.syslogManager();
                    //更新syslog tag标志
                    DynamicConfigAppender.updateSyslogTag();
                    //是否开启log4j的debug
                    DynamicConfigAppender.enableDebug();
                    //更新log4j的日志限速
                    DynamicConfigAppender.fileAppenderAddBurstFilter();
                    //更新log4j的日志最大备份天数
                    DynamicConfigAppender.setLogMaxBackup();
                }
            } catch (Exception e) {
                LogTool.warn(ErrorType.CONFIG_ERROR, "update openrasp.yml failed: " + e.getMessage(), e);
            }
        }
    }

    private void addConfigFileMonitor() throws JNotifyException {
        if (watchId != null) {
            FileScanMonitor.removeMonitor(watchId);
        }
        watchId = FileScanMonitor.addMonitor(configFileDir, new FileScanListener() {
            @Override
            public void onFileCreate(File file) {
                reloadConfig(file);
            }

            @Override
            public void onFileChange(File file) {
                reloadConfig(file);
            }

            @Override
            public void onFileDelete(File file) {
                reloadConfig(file);
            }
        });
    }

    private void setConfigFromProperties(Config.Item item, Map<String, Object> properties, boolean isInit) throws Exception {
        String key = item.key;
        String value = item.defaultValue;
        if (properties != null) {
            Object object = properties.get(item.key);
            if (object != null) {
                value = String.valueOf(object);
            }
        }
        try {
            setConfig(key, value, isInit);
        } catch (Exception e) {
            // 出现解析问题使用默认值
            setConfig(key, item.defaultValue, false);
            String message = "set config " + item.key + " failed, use default value: " + item.defaultValue;
            LogTool.warn(ErrorType.CONFIG_ERROR, message + ", because: " + e.getMessage(), e);
        }
    }

    private void handleException(String message, Exception e) {
        LogTool.warn(ErrorType.CONFIG_ERROR, message, e);
    }

    private static class ConfigHolder {
        static Config instance = new Config();
    }

    /**
     * 获取配置单例
     *
     * @return Config单例对象
     */
    public static Config getConfig() {
        return ConfigHolder.instance;
    }

    /**
     * 获取当前jar包所在目录
     *
     * @return 当前jar包目录
     */
    public String getBaseDirectory() {
        return baseDirectory;
    }

    /**
     * 获取js脚本所在目录
     *
     * @return js脚本目录
     */
    public String getScriptDirectory() {
        return baseDirectory + "/plugins";
    }

    /**
     * 获取自定义插入 html 页面的 js 脚本
     *
     * @return js脚本内容
     */
    public String getCustomResponseScript() {
        return CustomResponseHtml.getInstance() != null ? CustomResponseHtml.getInstance().getContent() : null;
    }

    @Override
    public void onDirectoryCreate(File file) {
        reloadConfigDir(file);
    }

    @Override
    public void onDirectoryDelete(File file) {
        reloadConfigDir(file);
    }

    @Override
    public void onFileCreate(File file) {
        // ignore
    }

    @Override
    public void onFileChange(File file) {
        // ignore
    }

    @Override
    public void onFileDelete(File file) {
        // ignore
    }

    private void reloadConfigDir(File directory) {
        try {
            if (directory.getName().equals(CustomResponseHtml.CUSTOM_RESPONSE_BASE_DIR)) {
                CustomResponseHtml.load(baseDirectory);
            } else if (directory.getName().equals(CONFIG_DIR_NAME)) {
                reloadConfig(new File(configFileDir + File.separator + CONFIG_FILE_NAME));
            }
        } catch (Exception e) {
            LogTool.warn(ErrorType.CONFIG_ERROR, "update " + directory.getAbsolutePath() +
                    " failed: " + e.getMessage(), e);
        }
    }

    //--------------------可以通过插件修改的配置项-----------------------------------

    /**
     * 获取Js引擎执行超时时间
     *
     * @return 超时时间
     */
    public long getPluginTimeout() {
        return pluginTimeout;
    }

    /**
     * 配置Js引擎执行超时时间
     *
     * @param pluginTimeout 超时时间
     */
    public synchronized void setPluginTimeout(String pluginTimeout) {
        long value = Long.parseLong(pluginTimeout);
        if (value <= 0) {
            throw new ConfigLoadException(Item.PLUGIN_TIMEOUT_MILLIS.toString() + " must be greater than 0");
        }
        this.pluginTimeout = value;
    }

    /**
     * 设置需要插入自定义html的页面path前缀
     *
     * @return 页面path前缀
     */
    public String getInjectUrlPrefix() {
        return injectUrlPrefix;
    }

    /**
     * 获取需要插入自定义html的页面path前缀
     *
     * @param injectUrlPrefix 页面path前缀
     */
    public synchronized void setInjectUrlPrefix(String injectUrlPrefix) {
        StringBuilder injectPrefix = new StringBuilder(injectUrlPrefix);
        while (injectPrefix.length() > 0 && injectPrefix.charAt(injectPrefix.length() - 1) == '/') {
            injectPrefix.deleteCharAt(injectPrefix.length() - 1);
        }
        this.injectUrlPrefix = injectPrefix.toString();
    }

    /**
     * 保存HTTP请求体时最大保存长度
     *
     * @return 最大长度
     */
    public int getBodyMaxBytes() {
        return bodyMaxBytes;
    }

    /**
     * 配置保存HTTP请求体时最大保存长度
     *
     * @param bodyMaxBytes
     */
    public synchronized void setBodyMaxBytes(String bodyMaxBytes) {
        int value = Integer.parseInt(bodyMaxBytes);
        if (value <= 0) {
            throw new ConfigLoadException(Item.BODY_MAX_BYTES.toString() + " must be greater than 0");
        }
        this.bodyMaxBytes = value;
    }

    public int getSqlSlowQueryMinCount() {
        return sqlSlowQueryMinCount;
    }

    public synchronized void setSqlSlowQueryMinCount(String sqlSlowQueryMinCount) {
        int value = Integer.parseInt(sqlSlowQueryMinCount);
        if (value < 0) {
            throw new ConfigLoadException(Item.SQL_SLOW_QUERY_MIN_ROWS.toString() + " can not be less than 0");
        }
        this.sqlSlowQueryMinCount = value;
    }

    /**
     * 需要忽略的挂钩点
     *
     * @return 需要忽略的挂钩点列表
     */
    public String[] getIgnoreHooks() {
        return this.ignoreHooks;
    }

    /**
     * 配置需要忽略的挂钩点
     *
     * @param ignoreHooks
     */
    public synchronized void setIgnoreHooks(String ignoreHooks) {
        this.ignoreHooks = ignoreHooks.replace(" ", "").split(",");
    }

    /**
     * 反射hook点传递给插件栈信息的最大深度
     *
     * @return 栈信息最大深度
     */
    public int getPluginMaxStack() {
        return pluginMaxStack;
    }

    /**
     * 设置反射hook点传递给插件栈信息的最大深度
     *
     * @param pluginMaxStack 栈信息最大深度
     */
    public synchronized void setPluginMaxStack(String pluginMaxStack) {
        int value = Integer.parseInt(pluginMaxStack);
        if (value < 0) {
            throw new ConfigLoadException(Item.PLUGIN_MAX_STACK.toString() + " can not be less than 0");
        }
        this.pluginMaxStack = value;
    }

    /**
     * 获取反射监控的方法
     *
     * @return 需要监控的反射方法
     */
    public String[] getReflectionMonitorMethod() {
        return reflectionMonitorMethod;
    }

    /**
     * 设置反射监控的方法
     *
     * @param reflectionMonitorMethod 监控的方法
     */
    public synchronized void setReflectionMonitorMethod(String reflectionMonitorMethod) {
        this.reflectionMonitorMethod = reflectionMonitorMethod.replace(" ", "").split(",");
    }

    /**
     * 获取 rasp id
     *
     * @return rasp id
     */
    public String getRaspId() {
        return raspId;
    }

    /**
     * 设置 rasp id
     *
     * @param raspId rasp id
     */
    public synchronized void setRaspId(String raspId) {
        this.raspId = raspId;
    }

    /**
     * 获取拦截自定义页面的url
     *
     * @return 拦截页面url
     */
    public String getBlockUrl() {
        return blockUrl;
    }

    /**
     * 设置拦截页面url
     *
     * @param blockUrl 拦截页面url
     */
    public synchronized void setBlockUrl(String blockUrl) {
        this.blockUrl = StringUtils.isEmpty(blockUrl) ? Item.BLOCK_REDIRECT_URL.defaultValue : blockUrl;
    }

    /**
     * 获取允许传入插件的ognl表达式的最短长度
     *
     * @return ognl表达式最短长度
     */
    public int getOgnlMinLength() {
        return ognlMinLength;
    }

    /**
     * 配置允许传入插件的ognl表达式的最短长度
     *
     * @param ognlMinLength ognl表达式最短长度
     */
    public synchronized void setOgnlMinLength(String ognlMinLength) {
        int value = Integer.parseInt(ognlMinLength);
        if (value <= 0) {
            throw new ConfigLoadException(Item.OGNL_EXPRESSION_MIN_LENGTH.toString() + " must be greater than 0");
        }
        this.ognlMinLength = value;
    }

    /**
     * 获取拦截状态码
     *
     * @return 状态码
     */
    public int getBlockStatusCode() {
        return blockStatusCode;
    }

    /**
     * 设置拦截状态码
     *
     * @param blockStatusCode 状态码
     */
    public synchronized void setBlockStatusCode(String blockStatusCode) {
        int value = Integer.parseInt(blockStatusCode);
        if (value < 100 || value > 999) {
            throw new ConfigLoadException(Item.BLOCK_STATUS_CODE.toString() + " must be between [100,999]");
        }
        this.blockStatusCode = value;
    }

    /**
     * 获取 debugLevel 级别
     * 0是关闭，非0开启
     *
     * @return debugLevel 级别
     */
    public int getDebugLevel() {
        return debugLevel;
    }

    /**
     * 设置 LRU 内容匹配开关
     *
     * @param lruCompareEnable lru 匹配开关
     */
    public synchronized void setLruCompareEnable(String lruCompareEnable) {
        boolean value = Boolean.parseBoolean(lruCompareEnable);
        if (value != this.lruCompareEnable) {
            this.lruCompareEnable = value;
            commonLRUCache.clear();
        }
    }

    /**
     * 获取 LRU 内容匹配开关
     *
     * @return LRU 内容匹配开关
     */
    public boolean getLruCompareEnable() {
        return lruCompareEnable;
    }

    /**
     * 设置 LRU 匹配最长字节
     *
     * @param lruCompareLimit LRU 匹配最长字节
     */
    public synchronized void setLruCompareLimit(String lruCompareLimit) {
        int value = Integer.parseInt(lruCompareLimit);
        if (value <= 0 || value > 102400) {
            throw new ConfigLoadException(Item.LRU_COMPARE_LIMIT.toString() + " must be between [1,102400]");
        }
        if (value < this.lruCompareLimit) {
            commonLRUCache.clear();
        }
        this.lruCompareLimit = value;
    }

    /**
     * 获取 LRU 匹配最长字节
     *
     * @return LRU 匹配最长字节
     */
    public int getLruCompareLimit() {
        return lruCompareLimit;
    }

    /**
     * 是否开启调试
     *
     * @return true 代表开启
     */
    public synchronized boolean isDebugEnabled() {
        return debugLevel > 0;
    }

    /**
     * 设置 debugLevel 级别
     *
     * @param debugLevel debugLevel 级别
     */
    public synchronized void setDebugLevel(String debugLevel) {
        this.debugLevel = Integer.parseInt(debugLevel);
        if (this.debugLevel < 0) {
            this.debugLevel = 0;
        } else if (this.debugLevel > 0) {
            String debugEnableMessage = "[OpenRASP] Debug output enabled, debug_level=" + debugLevel;
            System.out.println(debugEnableMessage);
            LOGGER.info(debugEnableMessage);
        }
    }

    /**
     * 获取检测算法配置
     *
     * @return 配置的 json 对象
     */
    public JsonObject getAlgorithmConfig() {
        return algorithmConfig;
    }

    /**
     * 设置检测算法配置
     *
     * @param json 配置内容
     */
    public synchronized void setAlgorithmConfig(String json) {
        this.algorithmConfig = new JsonParser().parse(json).getAsJsonObject();
        try {
            JsonArray result = null;
            JsonElement elements = ConfigurableChecker.getElement(algorithmConfig,
                    "sql_exception", "mysql");
            if (elements != null) {
                JsonElement e = elements.getAsJsonObject().get("error_code");
                if (e != null) {
                    result = e.getAsJsonArray();
                }
            }
            HashSet<Integer> errorCodes = new HashSet<Integer>();
            if (result != null) {
                if (result.size() > MAX_SQL_EXCEPTION_CODES_CONUT) {
                    LOGGER.warn("size of RASP.algorithmConfig.sql_exception.error_code can not be greater than "
                            + MAX_SQL_EXCEPTION_CODES_CONUT);
                }
                for (JsonElement element : result) {
                    try {
                        errorCodes.add(element.getAsInt());
                    } catch (Exception e) {
                        LOGGER.warn("failed to add a json error code element: "
                                + element.toString() + ", " + e.getMessage(), e);
                    }
                }
            } else {
                LOGGER.warn("failed to get sql_exception.${DB_TYPE}.error_code from algorithm config");
            }
            this.sqlErrorCodes = errorCodes;
            LOGGER.info("mysql sql error codes: " + this.sqlErrorCodes.toString());
        } catch (Exception e) {
            LOGGER.warn("failed to get json error code element: " + e.getMessage(), e);
        }
    }

    /**
     * 获取 sql 异常检测过滤的 sql 错误码
     *
     * @return sql 错误码列表
     */
    public HashSet<Integer> getSqlErrorCodes() {
        return sqlErrorCodes;
    }

    /**
     * 获取请求参数编码
     *
     * @return 请求参数编码
     */
    public String getRequestParamEncoding() {
        return requestParamEncoding;
    }

    /**
     * 设置请求参数编码
     * 当该配置不为空的情况下，将会允许 hook 点（如 request hook 点）能够根据设置的编码先于用户获取参数
     * （注意：如果设置了该编码，那么所有的请求参数都将按照这个编码解码，如果用户对参数有多种编码，建议不要添加此配置）
     * 当该配置为空的情况下，只有用户获取了参数之后，才会允许 hook 点获取参数，从而防止乱码问题
     *
     * @param requestParamEncoding 请求参数编码
     */
    public synchronized void setRequestParamEncoding(String requestParamEncoding) {
        this.requestParamEncoding = requestParamEncoding;
    }


    /**
     * 获取响应的contentType类型
     *
     * @return 返回contentType类型
     */
    public String getBlockJson() {
        return blockJson;
    }

    /**
     * 设置响应的ContentType类型
     *
     * @param blockJson ContentType
     */
    public synchronized void setBlockJson(String blockJson) {
        this.blockJson = blockJson;
    }


    /**
     * 获取响应的contentType类型
     *
     * @return 返回contentType类型
     */
    public String getBlockXml() {
        return blockXml;
    }

    /**
     * 设置响应的ContentType类型
     *
     * @param blockXml ContentType类型
     */
    public synchronized void setBlockXml(String blockXml) {
        this.blockXml = blockXml;
    }

    /**
     * 获取响应的contentType类型
     *
     * @return 返回contentType类型
     */
    public String getBlockHtml() {
        return blockHtml;
    }

    /**
     * 设置响应的ContentType类型
     *
     * @param blockHtml ContentType
     */
    public synchronized void setBlockHtml(String blockHtml) {
        this.blockHtml = blockHtml;
    }

    /**
     * 获取对于文件的include/reaFile等hook点，当文件不存在时，
     * 是否调用插件的开关状态
     *
     * @return 返回是否进入插件
     */
    public boolean getPluginFilter() {
        return pluginFilter;
    }

    /**
     * 设置对于文件的include/reaFile等hook点，当文件不存在时，
     * 是否调用插件的开关状态
     *
     * @param pluginFilter 开关状态:on/off
     */
    public synchronized void setPluginFilter(String pluginFilter) {
        this.pluginFilter = Boolean.parseBoolean(pluginFilter);
    }

    /**
     * 获取自定义的请求头
     *
     * @return 返回请求头
     */
    public String getClientIp() {
        return clientIp;
    }

    /**
     * 设置自定义的请求头
     *
     * @param clientIp 待设置的请求头信息
     */
    public synchronized void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    /**
     * 获取sql的lruCache的大小
     *
     * @return 缓存的大小
     */
    public int getSqlCacheCapacity() {
        return sqlCacheCapacity;
    }

    /**
     * 设置sql的lruCache的大小
     *
     * @param sqlCacheCapacity 待设置的缓存大小，默认大小为100
     */
    public synchronized void setSqlCacheCapacity(String sqlCacheCapacity) {
        int value = Integer.parseInt(sqlCacheCapacity);
        if (value < 0) {
            throw new ConfigLoadException(Item.SQL_CACHE_CAPACITY.toString() + " can not be less than 0");
        }
        this.sqlCacheCapacity = value;
        if (Config.commonLRUCache == null || Config.commonLRUCache.maxSize() != this.sqlCacheCapacity) {
            if (Config.commonLRUCache == null) {
                Config.commonLRUCache = new LRUCache<Object, String>(this.sqlCacheCapacity);
            } else {
                Config.commonLRUCache.clear();
                Config.commonLRUCache = new LRUCache<Object, String>(this.sqlCacheCapacity);
            }
        }
    }

    /**
     * 获取是否启用syslog开关状态
     *
     * @return syslog开关状态
     */
    public boolean getSyslogSwitch() {
        return syslogSwitch;
    }

    /**
     * 设置syslog开关状态
     *
     * @param syslogSwitch 待设置的syslog开关状态
     */
    public synchronized void setSyslogSwitch(String syslogSwitch) {
        this.syslogSwitch = Boolean.parseBoolean(syslogSwitch);
    }

    /**
     * 获取syslog上传日志的地址
     *
     * @return syslog上传日志的地址
     */
    public String getSyslogUrl() {
        return syslogUrl;
    }

    /**
     * 设置syslog上传日志的地址
     *
     * @param syslogUrl 待设置的syslog上传日志的地址
     */
    public synchronized void setSyslogUrl(String syslogUrl) {
        this.syslogUrl = syslogUrl;
    }

    /**
     * 获取syslog的layout中的tag字段信息
     *
     * @return syslog的layout中的tag字段信息
     */
    public String getSyslogTag() {
        return syslogTag;
    }

    /**
     * 设置 syslog 的 layout 中的 tag 字段信息
     *
     * @param syslogTag 待设置 syslog 的 layout 中的 tag 字段信息
     */
    public synchronized void setSyslogTag(String syslogTag) {
        this.syslogTag = syslogTag;
    }

    /**
     * 获取 syslog 的 facility 字段信息
     *
     * @return syslog 的 facility 字段信息
     */
    public int getSyslogFacility() {
        return syslogFacility;
    }

    /**
     * 设置 syslog 的 facility 字段信息
     *
     * @param syslogFacility 待设置 syslog 的 facility 字段信息
     */
    public synchronized void setSyslogFacility(String syslogFacility) {
        int value = Integer.parseInt(syslogFacility);
        if (!(value >= 0 && value <= 23)) {
            throw new ConfigLoadException(Item.SYSLOG_FACILITY.toString() + " must be between [0,23]");
        }
        this.syslogFacility = value;
    }

    /**
     * 获取 syslog 的重连时间
     *
     * @return syslog 的重连时间
     */
    public int getSyslogReconnectInterval() {
        return syslogReconnectInterval;
    }

    /**
     * 设置 syslog 的重连时间
     *
     * @param syslogReconnectInterval 待设置 syslog 的重连时间
     */
    public synchronized void setSyslogReconnectInterval(String syslogReconnectInterval) {
        int value = Integer.parseInt(syslogReconnectInterval);
        if (value <= 0) {
            throw new ConfigLoadException(Item.SYSLOG_RECONNECT_INTERVAL.toString() + " must be greater than 0");
        }
        this.syslogReconnectInterval = value;
    }

    /**
     * 获取日志每分钟上传的条数
     *
     * @return 日志每分钟上传的条数
     */
    public int getLogMaxBurst() {
        return logMaxBurst;
    }

    /**
     * 设置日志每分钟上传的条数
     *
     * @param logMaxBurst 待设置日志每分钟上传的条数
     */
    public synchronized void setLogMaxBurst(String logMaxBurst) {
        int value = Integer.parseInt(logMaxBurst);
        if (value < 0) {
            throw new ConfigLoadException(Item.LOG_MAXBURST.toString() + " can not be less than 0");
        }
        this.logMaxBurst = value;
    }

    /**
     * 获取是否禁用全部 hook 点
     *
     * @return 是否禁用全部 hook 点
     */
    public boolean getHookWhiteAll() {
        return hookWhiteAll;
    }

    /**
     * 设置是否禁用全部 hook 点
     *
     * @param hookWhiteAll 是否禁用全部hook点
     */
    public synchronized void setHookWhiteAll(String hookWhiteAll) {
        this.hookWhiteAll = Boolean.parseBoolean(hookWhiteAll);
    }

    /**
     * 获取是否禁用全部 hook 点
     *
     * @return 是否禁用全部 hook 点
     */
    public boolean getDisableHooks() {
        return disableHooks;
    }

    /**
     * 设置是否禁用全部hook点，
     *
     * @param disableHooks 是否禁用全部hook点
     */
    public synchronized void setDisableHooks(String disableHooks) {
        this.disableHooks = Boolean.parseBoolean(disableHooks);
    }

    /**
     * 获取云控的开关状态
     *
     * @return 云控开关状态
     */
    public boolean getCloudSwitch() {
        return cloudSwitch;
    }

    /**
     * 设置云控的开关状态
     *
     * @param cloudSwitch 待设置的云控开关状态
     */
    public synchronized void setCloudSwitch(String cloudSwitch) {
        this.cloudSwitch = Boolean.parseBoolean(cloudSwitch);
    }

    /**
     * 获取云控地址
     *
     * @return 返回云控地址
     */
    public String getCloudAddress() {
        return cloudAddress;
    }

    /**
     * 设置云控的地址，
     *
     * @param cloudAddress 待设置的云控地址
     */
    public synchronized void setCloudAddress(String cloudAddress) {
        this.cloudAddress = cloudAddress;
    }

    /**
     * 获取云控的请求的 appid
     *
     * @return 云控的请求的 appid
     */
    public String getCloudAppId() {
        return cloudAppId;
    }

    /**
     * 设置云控的 appid
     *
     * @param cloudAppId 待设置的云控的 appid
     */
    public synchronized void setCloudAppId(String cloudAppId) {
        this.cloudAppId = cloudAppId;
    }

    /**
     * 获取云控的请求的 appSecret
     *
     * @return 云控的请求的 appSecret
     */
    public String getCloudAppSecret() {
        return cloudAppSecret;
    }

    /**
     * 设置云控的 appSecret
     *
     * @param cloudAppSecret 待设置的云控的 appSecret
     */
    public synchronized void setCloudAppSecret(String cloudAppSecret) {
        this.cloudAppSecret = cloudAppSecret;
    }

    /**
     * 获取云控的心跳请求间隔，
     *
     * @return 云控的心跳请求间隔
     */
    public int getHeartbeatInterval() {
        return heartbeatInterval;
    }

    /**
     * 设置云控的心跳请求间隔
     *
     * @param heartbeatInterval 待设置的云控心跳请求间隔
     */
    public synchronized void setHeartbeatInterval(String heartbeatInterval) {
        int value = Integer.parseInt(heartbeatInterval);
        if (!(value >= 10 && value <= 1800)) {
            throw new ConfigLoadException(Item.HEARTBEAT_INTERVAL.toString() + " must be between [10,1800]");
        }
        this.heartbeatInterval = value;
    }

    /**
     * 获取java反编译的开关状态
     *
     * @return java反编译的开关状态
     */
    public boolean getDecompileEnable() {
        return decompileEnable;
    }

    /**
     * 设置java反编译的开关状态
     *
     * @param decompileEnable 待设置java反编译的开关状态
     */
    public synchronized void setDecompileEnable(String decompileEnable) {
        this.decompileEnable = Boolean.parseBoolean(decompileEnable);
    }

    /**
     * 获取response header数组
     *
     * @return response header数组
     */
    public Map<Object, Object> getResponseHeaders() {
        return responseHeaders;
    }

    /**
     * 设置response header数组
     *
     * @param responseHeaders 待设置response header数组
     */
    public synchronized void setResponseHeaders(Map<Object, Object> responseHeaders) {
        for (Map.Entry<Object, Object> entry : responseHeaders.entrySet()) {
            Object k = entry.getKey();
            Object v = entry.getValue();
            if (k == null || v == null) {
                throw new ConfigLoadException("the value of " + Item.RESPONSE_HEADERS.toString() +
                        "'s key and value can not be null");
            }
            if (!Reflection.isPrimitiveType(v) && !(v instanceof String)) {
                throw new ConfigLoadException("the type of " + Item.RESPONSE_HEADERS.toString() +
                        "'s value must be primitive type or String, can not be " + v.getClass().getName());
            }
            String key = v.toString();
            String value = v.toString();
            if (key.length() == 0 || key.length() > 200) {
                throw new ConfigLoadException("the length of " + Item.RESPONSE_HEADERS.toString() +
                        "'s key must be between [1,200]");
            }
            if (value.length() == 0 || value.length() > 200) {
                throw new ConfigLoadException("the length of " + Item.RESPONSE_HEADERS.toString() +
                        "'s value must be between [1,200]");
            }
        }
        this.responseHeaders = responseHeaders;
        LOGGER.info(RESPONSE_HEADERS + ": " + responseHeaders);
    }

    public synchronized void setHooksWhite(TreeMap<String, Integer> whiteList) {
        HookWhiteModel.init(whiteList);
        LOGGER.info(HOOKS_WHITE + ": " + whiteList);
    }

    /**
     * 获取log4j最大日志备份天数
     *
     * @return log4j最大日志备份天数
     */
    public int getLogMaxBackUp() {
        return logMaxBackUp;
    }

    /**
     * 设置log4j最大日志备份天数,默认30天
     *
     * @param logMaxBackUp log4j最大日志备份天数
     */
    public synchronized void setLogMaxBackUp(String logMaxBackUp) {
        int value = Integer.parseInt(logMaxBackUp) + 1;
        if (value <= 0) {
            throw new ConfigLoadException(Item.LOG_MAX_BACKUP.toString() + " can not be less than 0");
        }
        this.logMaxBackUp = value;
    }

    /**
     * 获取agent是否开启cpu熔断策略
     *
     * @return 是否开启cpu熔断策略
     */
    public boolean getCpuUsageEnable() {
        return cpuUsageEnable;
    }

    /**
     * 设置agent是否开启cpu熔断策略
     *
     * @param cpuUsageEnable agent是否开启cpu熔断策略
     */
    public synchronized void setCpuUsageEnable(String cpuUsageEnable) {
        this.cpuUsageEnable = Boolean.parseBoolean(cpuUsageEnable);
        try {
            CpuMonitorManager.resume(this.cpuUsageEnable);
        } catch (Throwable t) {
            // ignore 避免发生异常造成死循环
        }
    }

    /**
     * 获取熔断检测时间间隔，单位/秒
     *
     * @return 时间间隔
     */
    public int getCpuUsageCheckInterval() {
        return cpuUsageCheckInterval;
    }

    /**
     * 设置熔断检测时间间隔，单位/秒
     *
     * @param cpuUsageCheckInterval 时间间隔
     */
    public synchronized void setCpuUsageCheckInterval(String cpuUsageCheckInterval) {
        int interval = Integer.parseInt(cpuUsageCheckInterval);
        if (interval > 1800 || interval < 1) {
            throw new ConfigLoadException("cpu.usage.interval must be between [1,1800]");
        }
        this.cpuUsageCheckInterval = interval;
    }

    /**
     * 获取cpu的使用率的百分比
     *
     * @return cpu的使用率的百分比
     */
    public int getCpuUsagePercent() {
        return cpuUsagePercent;
    }

    /**
     * 设置cpu的使用率的百分比
     *
     * @param cpuUsagePercent cpu的使用率的百分比
     */
    public void setCpuUsagePercent(String cpuUsagePercent) {
        int value = Integer.parseInt(cpuUsagePercent);
        if (!(value >= 30 && value <= 100)) {
            throw new ConfigLoadException(Item.CPU_USAGE_PERCENT.toString() + " must be between [30,100]");
        }
        this.cpuUsagePercent = value;
    }

    /**
     * 获取是否进行 https 证书验证
     *
     * @return 是否进行 https 证书验证
     */
    public boolean isHttpsVerifyPeer() {
        return isHttpsVerifyPeer;
    }

    /**
     * 设置是否进行 https 证书验证
     *
     * @param httpsVerifyPeer agent是否开启cpu熔断策略
     */
    public synchronized void setHttpsVerifyPeer(String httpsVerifyPeer) {
        this.isHttpsVerifyPeer = Boolean.parseBoolean(httpsVerifyPeer);
    }
    //--------------------------统一的配置处理------------------------------------

    /**
     * 统一配置接口,通过 js 更改配置的入口
     *
     * @param key   配置名
     * @param value 配置值
     * @return 是否配置成功
     */
    public boolean setConfig(String key, String value, boolean isInit) throws Exception {
        try {
            boolean isHit = true;
            Object currentValue = null;
            if (Item.BLOCK_REDIRECT_URL.key.equals(key)) {
                setBlockUrl(value);
                currentValue = getBlockUrl();
            } else if (Item.BODY_MAX_BYTES.key.equals(key)) {
                setBodyMaxBytes(value);
                currentValue = getBodyMaxBytes();
            } else if (Item.HOOKS_IGNORE.key.equals(key)) {
                setIgnoreHooks(value);
                currentValue = Arrays.toString(getIgnoreHooks());
            } else if (Item.INJECT_URL_PREFIX.key.equals(key)) {
                setInjectUrlPrefix(value);
                currentValue = getInjectUrlPrefix();
            } else if (Item.OGNL_EXPRESSION_MIN_LENGTH.key.equals(key)) {
                setOgnlMinLength(value);
                currentValue = getOgnlMinLength();
            } else if (Item.PLUGIN_TIMEOUT_MILLIS.key.equals(key)) {
                setPluginTimeout(value);
                currentValue = getPluginTimeout();
            } else if (Item.PLUGIN_MAX_STACK.key.equals(key)) {
                setPluginMaxStack(value);
                currentValue = getPluginMaxStack();
            } else if (Item.SQL_SLOW_QUERY_MIN_ROWS.key.equals(key)) {
                setSqlSlowQueryMinCount(value);
                currentValue = getSqlSlowQueryMinCount();
            } else if (Item.BLOCK_STATUS_CODE.key.equals(key)) {
                setBlockStatusCode(value);
                currentValue = getBlockStatusCode();
            } else if (Item.DEBUG.key.equals(key)) {
                setDebugLevel(value);
                currentValue = getDebugLevel();
            } else if (Item.ALGORITHM_CONFIG.key.equals(key)) {
                setAlgorithmConfig(value);
                currentValue = value;
            } else if (Item.REQUEST_PARAM_ENCODING.key.equals(key)) {
                setRequestParamEncoding(value);
                currentValue = getRequestParamEncoding();
            } else if (Item.BLOCK_JSON.key.equals(key)) {
                setBlockJson(value);
                currentValue = getBlockJson();
            } else if (Item.BLOCK_XML.key.equals(key)) {
                setBlockXml(value);
                currentValue = getBlockXml();
            } else if (Item.BLOCK_HTML.key.equals(key)) {
                setBlockHtml(value);
                currentValue = getBlockHtml();
            } else if (Item.PLUGIN_FILTER.key.equals(key)) {
                setPluginFilter(value);
                currentValue = getPluginFilter();
            } else if (Item.CLIENT_IP_HEADER.key.equals(key)) {
                setClientIp(value);
                currentValue = getClientIp();
            } else if (Item.CLOUD_SWITCH.key.equals(key)) {
                setCloudSwitch(value);
                currentValue = getCloudSwitch();
            } else if (Item.CLOUD_ADDRESS.key.equals(key)) {
                setCloudAddress(value);
                currentValue = getCloudAddress();
            } else if (Item.CLOUD_APPID.key.equals(key)) {
                setCloudAppId(value);
                currentValue = getCloudAppId();
            } else if (Item.CLOUD_APPSECRET.key.equals(key)) {
                setCloudAppSecret(value);
                currentValue = getCloudAppSecret();
            } else if (Item.SQL_CACHE_CAPACITY.key.equals(key)) {
                setSqlCacheCapacity(value);
                currentValue = getSqlCacheCapacity();
            } else if (Item.SYSLOG_ENABLE.key.equals(key)) {
                setSyslogSwitch(value);
                currentValue = getSyslogSwitch();
            } else if (Item.SYSLOG_URL.key.equals(key)) {
                setSyslogUrl(value);
                currentValue = getSyslogUrl();
            } else if (Item.SYSLOG_TAG.key.equals(key)) {
                setSyslogTag(value);
                currentValue = getSyslogTag();
            } else if (Item.SYSLOG_FACILITY.key.equals(key)) {
                setSyslogFacility(value);
                currentValue = getSyslogFacility();
            } else if (Item.SYSLOG_RECONNECT_INTERVAL.key.equals(key)) {
                setSyslogReconnectInterval(value);
                currentValue = getSyslogReconnectInterval();
            } else if (Item.LOG_MAXBURST.key.equals(key)) {
                setLogMaxBurst(value);
                currentValue = getLogMaxBurst();
            } else if (Item.HEARTBEAT_INTERVAL.key.equals(key)) {
                setHeartbeatInterval(value);
                currentValue = getHeartbeatInterval();
            } else if (Item.DECOMPILE_ENABLE.key.equals(key)) {
                setDecompileEnable(value);
                currentValue = getDecompileEnable();
            } else if (Item.LOG_MAX_BACKUP.key.equals(key)) {
                setLogMaxBackUp(value);
                currentValue = getLogMaxBackUp();
            } else if (Item.CPU_USAGE_ENABLE.key.equals(key)) {
                setCpuUsageEnable(value);
                currentValue = getCpuUsageEnable();
            } else if (Item.CPU_USAGE_PERCENT.key.equals(key)) {
                setCpuUsagePercent(value);
                currentValue = getCpuUsagePercent();
            } else if (Item.HTTPS_VERIFY_SSL.key.equals(key)) {
                setHttpsVerifyPeer(value);
                currentValue = isHttpsVerifyPeer();
            } else if (Item.RASP_ID.key.equals(key)) {
                if (!isInit && !value.equals(raspId)) {
                    LOGGER.info("can not update the value of rasp.id at runtime");
                    return false;
                } else {
                    setRaspId(value);
                    currentValue = getRaspId();
                }
            } else if (Item.CPU_USAGE_INTERVAL.key.equals(key)) {
                setCpuUsageCheckInterval(value);
                currentValue = getCpuUsageCheckInterval();
            } else if (Item.LRU_COMPARE_ENABLE.key.equals(key)) {
                setLruCompareEnable(value);
                currentValue = getLruCompareEnable();
            } else if (Item.LRU_COMPARE_LIMIT.key.equals(key)) {
                setLruCompareLimit(value);
                currentValue = getLruCompareLimit();
            } else {
                isHit = false;
            }
            if (isHit) {
                if (currentValue == null) {
                    currentValue = value;
                }
                if (isInit) {
                    LOGGER.info(key + ": " + currentValue);
                } else {
                    LOGGER.info("configuration item \"" + key + "\" changed to \"" + currentValue + "\"");
                }
            } else {
                LOGGER.info("configuration item \"" + key + "\" doesn't exist");
                return false;
            }
        } catch (Exception e) {
            if (!isInit) {
                LOGGER.warn("configuration item \"" + key + "\" failed to change to \"" + value + "\"" + " because:" + e.getMessage());
            }
            // 初始化配置过程中,如果报错需要继续使用默认值执行
            if (!(e instanceof ConfigLoadException)) {
                throw new ConfigLoadException(e);
            }
            throw e;
        }
        return true;
    }

    private TreeMap<String, Integer> parseHookWhite(Map<Object, Object> hooks) {
        TreeMap<String, Integer> temp = new TreeMap<String, Integer>();
        for (Map.Entry<Object, Object> hook : hooks.entrySet()) {
            int codeSum = 0;
            if (hook.getValue() instanceof ArrayList) {
                @SuppressWarnings("unchecked")
                ArrayList<String> types = (ArrayList<String>) hook.getValue();
                if (hook.getKey().equals("*") && types.contains("all")) {
                    for (CheckParameter.Type type : CheckParameter.Type.values()) {
                        if (type.getCode() != 0) {
                            codeSum = codeSum + type.getCode();
                        }
                    }
                    temp.put("", codeSum);
                    return temp;
                } else if (types.contains("all")) {
                    for (CheckParameter.Type type : CheckParameter.Type.values()) {
                        if (type.getCode() != 0) {
                            codeSum = codeSum + type.getCode();
                        }
                    }
                    temp.put(hook.getKey().toString(), codeSum);
                } else {
                    for (String s : types) {
                        String hooksType = s.toUpperCase();
                        try {
                            Integer code = CheckParameter.Type.valueOf(hooksType).getCode();
                            codeSum = codeSum + code;
                        } catch (Exception e) {
//                            LogTool.traceWarn(ErrorType.CONFIG_ERROR, "Hook type " + s + " does not exist", e);
                        }
                    }
                    if (hook.getKey().equals("*")) {
                        temp.put("", codeSum);
                    } else {
                        temp.put(hook.getKey().toString(), codeSum);
                    }
                }
            }
        }
        return temp;
    }
}
