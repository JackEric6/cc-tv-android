package com.github.tvbox.osc.server;

import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * 单个 CMS 资源站的适配器契约。
 *
 * <p>适配器按 source key 或上游 API 特征选择。未知站点可以继续使用兼容适配器，
 * 已知站点则可以在不改变 RemoteServer 路由的情况下替换自己的请求、字段和回退规则。</p>
 */
interface CmsAdapter {
    /** 稳定的内部适配器标识，用于日志和测试。 */
    String id();

    /** 处理一次本地 CMS 请求。 */
    NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session,
                                  String fileName,
                                  Map<String, String> params,
                                  Map<String, String> files);
}
