package com.easymall;

public class BusinessException extends RuntimeException {
    final int status;
    final String code;
    public BusinessException(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    public static BusinessException missing() { return new BusinessException(404, "NOT_FOUND", "资源不存在"); }
    public static BusinessException conflict(String message) { return new BusinessException(409, "CONFLICT", message); }
}
