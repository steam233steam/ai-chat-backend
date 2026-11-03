/**
 * Service 支撑组件：有 Spring 依赖、但不适合暴露成业务接口的协作类。
 *
 * 例如意图限流、候选净化。它们可以被 Service.impl 注入，但不应被 Controller 直接依赖。
 */
package com.aichat.Service.support;
