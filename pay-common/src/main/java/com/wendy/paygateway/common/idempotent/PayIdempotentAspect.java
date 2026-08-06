package com.wendy.paygateway.common.idempotent;

import com.wendy.paygateway.common.exception.BizException;
import com.wendy.paygateway.common.exception.ErrorCode;
import com.wendy.paygateway.common.infra.IdempotentStore;
import com.wendy.paygateway.common.util.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * Aspect backing {@link PayIdempotent}.
 *
 * <p>It all hinges on {@code SET key PROCESSING NX EX ttl}: whoever wins the slot runs the business
 * logic and then overwrites the same key with the JSON of the return value. Requests that lost the
 * race inspect the current value to tell "still processing" from "already finished", and replay the
 * cached result in the latter case. If the business logic throws, the key is deleted so retries are
 * allowed.
 */
@Slf4j
@Aspect
@Component
@Order(20)
@RequiredArgsConstructor
public class PayIdempotentAspect {

    private static final String KEY_NAMESPACE = "pay:idem:";
    private static final String PROCESSING_FLAG = "__PROCESSING__";

    private final IdempotentStore idempotentStore;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint pjp, PayIdempotent idempotent) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        String key = KEY_NAMESPACE + idempotent.prefix() + ":" + evaluateKey(method, pjp.getArgs(), idempotent.key());
        Duration ttl = Duration.ofSeconds(idempotent.ttlSeconds());

        if (!idempotentStore.setIfAbsent(key, PROCESSING_FLAG, ttl)) {
            return handleDuplicate(key, method, idempotent);
        }

        try {
            Object result = pjp.proceed();
            if (idempotent.replayResult() && result != null) {
                idempotentStore.set(key, JsonUtils.toJson(result), ttl);
            }
            return result;
        } catch (Throwable e) {
            // A failed attempt must not block retries, otherwise the caller is stuck until the
            // idempotency window expires
            idempotentStore.delete(key);
            throw e;
        }
    }

    private Object handleDuplicate(String key, Method method, PayIdempotent idempotent) {
        String cached = idempotentStore.get(key);
        if (cached == null) {
            // The key expired right at this moment; reject as a duplicate rather than race the original
            log.warn("[Idempotency] duplicate request but the cached result already expired key={}", key);
            throw BizException.of(ErrorCode.REPEAT_SUBMIT);
        }
        if (PROCESSING_FLAG.equals(cached)) {
            log.warn("[Idempotency] first request still running, rejecting duplicate key={}", key);
            throw BizException.of(ErrorCode.PROCESSING);
        }
        if (!idempotent.replayResult()) {
            throw BizException.of(ErrorCode.REPEAT_SUBMIT);
        }
        log.info("[Idempotency] cache hit, replaying the first result key={}", key);
        return JsonUtils.parse(cached, method.getGenericReturnType());
    }

    private String evaluateKey(Method method, Object[] args, String expression) {
        EvaluationContext context = new StandardEvaluationContext();
        String[] names = nameDiscoverer.getParameterNames(method);
        if (names != null) {
            for (int i = 0; i < names.length; i++) {
                context.setVariable(names[i], args[i]);
            }
        }
        Object value = parser.parseExpression(expression).getValue(context);
        if (value == null) {
            throw new IllegalArgumentException("Idempotency key resolved to null: " + expression);
        }
        return String.valueOf(value);
    }
}
