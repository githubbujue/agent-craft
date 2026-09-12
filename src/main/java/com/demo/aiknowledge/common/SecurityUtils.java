package com.demo.aiknowledge.common;

import com.demo.aiknowledge.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录用户工具类 —— 「身份只信 token, 不信前端传参」原则的落地载体。
 *
 * <p>背景（修复的越权问题）:
 * 原实现中 Controller 直接使用请求参数里的 userId（如 ?userId=2）,
 * 登录用户 A 只要把参数改成 B 的 id, 就能读写 B 的会话与消息 —— 典型的
 * 水平越权（IDOR）。根因是"身份来源"错误: 请求参数是用户可以随意伪造的,
 * 而 JWT 直接签发了 userId, 是可信身份来源。
 *
 * <p>数据来源说明:
 * JwtFilter 校验 token 后, 会把 claims 里的 userId 作为 principal 写入
 * SecurityContext（见 JwtFilter#doFilterInternal）。本类只是"读取"该事实,
 * 不再重复解析 token。
 */
public final class SecurityUtils {

    /** 工具类不允许实例化 */
    private SecurityUtils() {
    }

    /**
     * 获取当前登录用户 id。
     *
     * @throws BusinessException 未携带有效 token 时抛出 INVALID_TOKEN
     */
    public static Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // 无认证信息 / 匿名用户 / 非认证状态 —— 均视为未登录
        if (auth == null || auth.getName() == null || !auth.isAuthenticated()) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
        try {
            // JwtFilter 将 claims.userId 设置为主键名, 故此处直接转换
            return Long.valueOf(auth.getName());
        } catch (NumberFormatException e) {
            // principal 不是数字说明 token 内容异常
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
    }

    /**
     * 当前用户是否为管理员（角色来自 JWT claims, JwtFilter 写入 ROLE_xxx 形式）。
     * 用于需要"管理员可见更多数据"的场景做显式判断。
     */
    public static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    /**
     * 资源归属校验: 资源所有者必须等于当前登录用户, 否则 403。
     *
     * <p>遵循最小权限原则: 管理员也不放行（管理端有独立的 /api/admin/** 接口,
     * 不应借用用户端接口越权读取他人会话）。
     *
     * @param resourceOwnerId 数据库里资源记录上的归属用户 id
     */
    public static void checkOwnership(Long resourceOwnerId) {
        if (resourceOwnerId == null || !resourceOwnerId.equals(getCurrentUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
