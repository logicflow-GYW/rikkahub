package me.rerere.rikkahub.exception

/**
 * 领域层通用异常：核心功能（消息/消息节点处理）在业务校验失败时抛出，
 * 供上层（UI / 订阅端）捕获并转为可读错误。保留自原 web 层的同名异常，
 * 剥离对 ktor 的依赖（原 status 字段仅 web 路由错误响应使用）。
 */
class BadRequestException(message: String) : RuntimeException(message)

class NotFoundException(message: String) : RuntimeException(message)
