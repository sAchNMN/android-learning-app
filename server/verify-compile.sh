#!/usr/bin/env bash
# ============================================================
# 后端语法自检：用最小 stub 编译 business/ 下的全部 Java
#
# 为什么需要这个：
#   RuoYi-Vue 是独立 clone，不进版本控制。没有它就无法 mvn package，
#   但改完代码又急需知道「有没有编译错误」。这个脚本用 23 个 stub
#   顶替若依与框架的类，把业务代码单独丢给 javac。
#
# 能查出：语法错误、类型不匹配、方法签名对不上、import 缺失
# 查不出：Spring 运行时行为、MyBatis SQL 语法、Bean 装配
#        —— 这些必须 mvn package + 真跑一次
#
# 用法：bash server/verify-compile.sh
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
# 放仓库内而不是 /tmp：沙箱对 /tmp 的 rm 有拦截，且这里方便排查
WORK="$ROOT/.verify-stub"

# stub 内容是固定的，直接覆盖写，不删目录（避免触发 safe-delete 拦截）
mkdir -p "$WORK"

mk() {
    mkdir -p "$WORK/$(dirname "$1")"
    printf '%s\n' "$2" > "$WORK/$1"
}

# ---------- 若依 ----------
mk com/ruoyi/common/exception/ServiceException.java 'package com.ruoyi.common.exception;
public class ServiceException extends RuntimeException {
    public ServiceException(String message) { super(message); }
}'

mk com/ruoyi/common/core/domain/AjaxResult.java 'package com.ruoyi.common.core.domain;
import java.util.HashMap;
public class AjaxResult extends HashMap<String, Object> {
    public static AjaxResult success() { return new AjaxResult(); }
    public static AjaxResult success(Object data) { return new AjaxResult(); }
    public static AjaxResult success(String msg, Object data) { return new AjaxResult(); }
    public static AjaxResult error(String msg) { return new AjaxResult(); }
}'

mk com/ruoyi/common/utils/SecurityUtils.java 'package com.ruoyi.common.utils;
public class SecurityUtils {
    public static Long getUserId() { return 1L; }
    public static String getUsername() { return "admin"; }
}'

mk com/ruoyi/common/annotation/Anonymous.java 'package com.ruoyi.common.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE, ElementType.METHOD})
public @interface Anonymous {}'

# ---------- Spring ----------
mk org/springframework/beans/factory/annotation/Autowired.java 'package org.springframework.beans.factory.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target({ElementType.FIELD, ElementType.CONSTRUCTOR, ElementType.METHOD})
public @interface Autowired { boolean required() default true; }'

mk org/springframework/stereotype/Service.java 'package org.springframework.stereotype;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
public @interface Service { String value() default ""; }'

mk org/springframework/transaction/annotation/Transactional.java 'package org.springframework.transaction.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE, ElementType.METHOD})
public @interface Transactional {
    Class<? extends Throwable>[] rollbackFor() default {};
    String value() default "";
}'

mk org/springframework/dao/DuplicateKeyException.java 'package org.springframework.dao;
public class DuplicateKeyException extends RuntimeException {
    public DuplicateKeyException(String message) { super(message); }
}'

mk org/springframework/data/redis/core/ValueOperations.java 'package org.springframework.data.redis.core;
import java.util.concurrent.TimeUnit;
public interface ValueOperations<K, V> {
    Boolean setIfAbsent(K key, V value, long timeout, TimeUnit unit);
    void set(K key, V value);
    V get(Object key);
}'

mk org/springframework/data/redis/core/RedisTemplate.java 'package org.springframework.data.redis.core;
public class RedisTemplate<K, V> {
    public ValueOperations<K, V> opsForValue() { return null; }
    public Boolean delete(K key) { return Boolean.TRUE; }
}'

# 类级注解：字符串数组 value
for a in RestController RequestMapping GetMapping PostMapping PutMapping DeleteMapping; do
    mk "org/springframework/web/bind/annotation/$a.java" "package org.springframework.web.bind.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE, ElementType.METHOD})
public @interface $a { String[] value() default {}; }"
done

# 参数级注解
for a in PathVariable RequestParam RequestBody; do
    mk "org/springframework/web/bind/annotation/$a.java" "package org.springframework.web.bind.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.PARAMETER)
public @interface $a { String value() default \"\"; boolean required() default true; }"
done

# ---------- MyBatis ----------
for a in Select Insert Update Delete; do
    mk "org/apache/ibatis/annotations/$a.java" "package org.apache.ibatis.annotations;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
public @interface $a { String[] value(); }"
done

mk org/apache/ibatis/annotations/Param.java 'package org.apache.ibatis.annotations;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.PARAMETER)
public @interface Param { String value(); }'

mk org/apache/ibatis/annotations/Options.java 'package org.apache.ibatis.annotations;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
public @interface Options {
    boolean useGeneratedKeys() default false;
    String keyProperty() default "";
    String keyColumn() default "";
}'

# ---------- 编译 ----------
FILES=$(find "$WORK" "$ROOT/business" -name '*.java')
COUNT=$(echo "$FILES" | wc -l | tr -d ' ')

echo "编译 $COUNT 个文件（业务代码 + stub）..."
javac -encoding UTF-8 -d "$WORK/out" $FILES

echo "OK：全部通过，产物在 $WORK/out"
