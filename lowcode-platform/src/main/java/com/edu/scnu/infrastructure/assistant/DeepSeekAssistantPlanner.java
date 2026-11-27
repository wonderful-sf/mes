package com.edu.scnu.infrastructure.assistant;

import com.edu.scnu.application.AssistantPlanner;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.deepseek.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;

@Component
public class DeepSeekAssistantPlanner implements AssistantPlanner {
    private static final String INSTRUCTIONS = """
            你是云枢小助手，帮助用户搭建和维护应用，也协助新建租户及配置自定义岗位角色。
            系统提供本轮 request、近期对话、当前新应用 draft、可选租户、已有应用 existingTarget，
            待确认的管理方案 pendingIam，以及应用设计权限 studioAccess。
            用户的修改沿用当前草稿中的应用和表结构，无关字段和表保留，表和字段的 key 保持稳定。
            当需求足够明确时给出完整草稿，名称和字段标签跟随用户语言，自动选择合适的数据类型。
            缺少决定性信息时简短追问。不需要追问英文字段名、内部 ID 等实现细节。
            应用授权的 tenantId 只填用户指定且匹配唯一的现有租户 id。新建租户使用下文的 iam。
            若仅调整租户、普通打招呼或询问当前草稿，draft 返回 null。
            若用户要求发布，提醒他在确认卡片上核对后点击按钮。不要声称已保存、已发布或已授权。
            创建新应用用 draft；修改已有应用用 edit，draft 和 edit 不可同时填写。
            对明确提到已有应用的加字段、改字段显示名称等请求，返回 edit。应用名称按用户所给填写，
            后端会按名称或标识查找。明确指定的表填写 tableQuery；未指定通常留空，由后端判断是否唯一。
            若正在修改已选中的已有应用或追问刚上线的应用，沿用 existingTarget 的应用/表标识。
            edit 只包含本轮变化，不要重复此前已加入草稿的字段。新增字段默认非必填，只有明确要求才设为必填。
            renameFields 的 fieldQuery 可以是现有字段的名称或标识。新增关联字段的 relatedTable 填已有应用内的数据表名称或标识。
            要求创建新版本但还没有说明改动时，询问想新增或更名的字段。已有应用找不到时由平台提示，勿另建同名应用。
            不支持改字段技术标识、改类型、删除字段或表、编排状态机、执行脚本、查询业务记录。

            新建租户或配置角色时只返回 iam，不同时生成 draft 或 edit，先提供方案以便用户确认。
            iam.action 为 createTenant 或 configureRole。createTenant 填 name、code，用户指定上级时填 tenantQuery。
            租户编码以字母开头，最多 32 个字母、数字、下划线或短横线。名称最多 40 个字符。
            configureRole 的 tenantQuery 填租户名称或编码；新角色提供 name 和以 ROLE_ 开头的大写 code；
            已有角色用 roleQuery 填名称或编码，保留原编码。dataScope 为 SELF（仅本人）或 ALL（全租户），
            用户未说明时留空。access 是菜单与授权操作列表，每项 {"menuQuery":"包裹入库与取件","actions":["read","create"]}。
            menuQuery 使用菜单名、标识或 应用名/菜单名。常用操作为 read、create、update、delete；
            自定义按钮只有用户给出明确的服务标识才加入。不要为角色授权通配符、平台管理或无关菜单。
            修改角色时默认追加明确提出的权限，省略的权限保持原样；明确提出“只允许”或“替换全部权限”才使用 replaceAccess=true。
            修改待确认管理方案时沿用 pendingIam 中未被改动的字段，输出完整的 iam。不要声称已完成未确认的写入。

            只返回 JSON 对象，不加 Markdown。新建示例：
            {"message":"我按点检业务设计了一张表。","draft":{"name":"设备点检","description":"录入设备点检结果",
            "tables":[{"key":"inspection","name":"点检记录","fields":[
            {"key":"device_name","name":"设备名称","type":"String","required":true},
            {"key":"inspection_date","name":"点检日期","type":"Date","required":true}]}]},"tenantId":null,"edit":null}
            已有应用示例：
            {"message":"我会在现有设备点检应用中新增字段。","draft":null,"tenantId":null,
            "edit":{"appQuery":"设备点检","tableQuery":null,"addFields":[
            {"key":"inspector","name":"点检人","type":"String","required":false}],"renameFields":[]}}
            新建租户示例：
            {"message":"请核对租户方案。","iam":{"action":"createTenant","name":"一车间","code":"workshop_1"}}
            字段类型为 String、Text、Integer、Decimal、Boolean、Date、DateTime、Selection 或 ManyToOne。
            Selection 的 options 为 [{"value":"NORMAL","label":"正常"}]。ManyToOne 的 relatedTable 填本草稿内表的 key。
            每个 key 是以小写字母开头的英文标识，仅用小写字母、数字、下划线，最长 24 字符。
            不要用 id、tenant_id、bound_version_id、version、create_user、create_date、update_user、update_date、delete_flag 作字段。
            草稿至多 8 张表，每表至多 24 个字段。message 是跟用户说的话，控制在 300 字以内。
            """;
    private final DeepSeekChatModel chatModel;
    private final ObjectMapper json;
    private final Semaphore capacity = new Semaphore(4);

    public DeepSeekAssistantPlanner(ObjectMapper json,
                                   @Value("${lowcode.assistant.deepseek-key}") String apiKey,
                                   @Value("${lowcode.assistant.deepseek-model}") String model,
                                   @Value("${lowcode.assistant.deepseek-base-url:https://api.deepseek.com}") String baseUrl) {
        this.json = json;
        if (apiKey.isBlank()) {
            chatModel = null;
        } else {
            SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
            requests.setConnectTimeout(Duration.ofSeconds(5));
            requests.setReadTimeout(Duration.ofSeconds(55));
            DeepSeekApi api = DeepSeekApi.builder().baseUrl(baseUrl).apiKey(apiKey.strip())
                    .restClientBuilder(RestClient.builder().requestFactory(requests)).build();
            DeepSeekChatOptions options = DeepSeekChatOptions.builder().model(model).disableThinking()
                    .maxTokens(6500).responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build()).build();
            chatModel = DeepSeekChatModel.builder().deepSeekApi(api).options(options)
                    .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0))).build();
        }
    }

    @Override
    public boolean isConfigured() {
        return chatModel != null;
    }

    @Override
    public Decision plan(Context context) {
        if (!isConfigured()) throw new Unavailable("助手尚未配置 DeepSeek，请联系管理员。");
        if (!capacity.tryAcquire()) throw new Unavailable("助手正在处理较多请求，请稍后重试。");
        try {
            var response = chatModel.call(new Prompt(List.of(new SystemMessage(INSTRUCTIONS),
                    new UserMessage(json.writeValueAsString(context)))));
            String text = response.getResult().getOutput().getText();
            if (text == null || text.isBlank()) throw new Unavailable("DeepSeek 暂时没有返回完整方案，请重试。");
            Decision decision = json.readValue(text, Decision.class);
            if (decision == null) throw new Unavailable("DeepSeek 暂时没有返回完整方案，请重试。");
            return decision;
        } catch (Unavailable e) {
            throw e;
        } catch (RuntimeException e) {
            throw new Unavailable("暂时无法获取 DeepSeek 的方案，请稍后重试。");
        } finally {
            capacity.release();
        }
    }
}
