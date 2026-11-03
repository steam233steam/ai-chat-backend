/**
 * Service 接口层。
 *
 * 约定：
 * - 本包只放业务接口（Controller 依赖这里）；
 * - 实现统一放 {@code Service.impl}；
 * - 跨领域/无接口的服务组件（限流、候选净化等）放 {@code Service.support}；
 * - 返回值/入参对象放 {@code Dto}，不要把 DTO 或结果类混进接口包。
 */
package com.aichat.Service;
