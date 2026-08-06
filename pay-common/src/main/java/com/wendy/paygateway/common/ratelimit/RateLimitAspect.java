package com.wendy.paygateway.common.ratelimit;

import com.wendy.paygateway.common.config.PayProperties;
import com.wendy.paygateway.common.exception.BizException;
import com.wendy.paygateway.common.exception.ErrorCode;
import com.wendy.paygateway.common.infra.SlidingWindowRateLimiter;
import com.wendy.paygateway.common.util.WebUtils;
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

/** Rate-limit aspect. It runs before the idempotency aspect: hold back the flood first, then dedupe. */
@Slf4j
@Aspect
@Component
@Order(10)
@RequiredArgsConstructor
public class RateLimitAspect {

    private static final String KEY_NAMESPACE = "pay:rl:";
    public static final String GLOBAL_STRING = "global";

    private final SlidingWindowRateLimiter rateLimiter;
    private final PayProperties payProperties;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        if (!payProperties.getRatelimit().isEnabled()) {
            return pjp.proceed();
        }
        String key = KEY_NAMESPACE + rateLimit.name() + ":" + resolveDimension(pjp, rateLimit);
        boolean allowed = rateLimiter.tryAcquire(key, rateLimit.limit(), Duration.ofSeconds(rateLimit.windowSeconds()));
        if (!allowed) {
            log.warn("[Rate limit] rejected key={} limit={}/{}s", key, rateLimit.limit(), rateLimit.windowSeconds());
            throw BizException.of(ErrorCode.RATE_LIMITED);
        }
        return pjp.proceed();
    }

    private String resolveDimension(ProceedingJoinPoint pjp, RateLimit rateLimit) {
        return switch (rateLimit.dimension()) {
            case GLOBAL -> GLOBAL_STRING;
            case IP -> WebUtils.currentClientIp();
            case SPEL -> evaluate(pjp, rateLimit.key());
        };
    }

    private String evaluate(ProceedingJoinPoint pjp, String expression) {
        if (expression == null || expression.isEmpty()) {
            return GLOBAL_STRING;
        }
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        EvaluationContext context = new StandardEvaluationContext();
        String[] names = nameDiscoverer.getParameterNames(method);
        if (names != null) {
            for (int i = 0; i < names.length; i++) {
                context.setVariable(names[i], pjp.getArgs()[i]);
            }
        }
        Object value = parser.parseExpression(expression).getValue(context);
        return value == null ? "null" : String.valueOf(value);
    }
}
