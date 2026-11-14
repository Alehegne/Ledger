package com.example.Ledger.common.idempotency;

import com.example.Ledger.common.exception.BusinessException;
import com.example.Ledger.common.security.CurrentUserProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.annotation.Annotation;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class IdempotencyAspect {

    public static final String HEADER_NAME = "Idempotency-Key";
    private static final int MAX_KEY_LENGTH = 128;

    private final IdempotencyService idempotencyService;
    private final CurrentUserProvider currentUserProvider;
    private final ObjectMapper canonicalMapper;
    private final TransactionTemplate transactionTemplate;
    private final boolean enforceRequiredGlobally;

    public IdempotencyAspect(
            IdempotencyService idempotencyService,
            CurrentUserProvider currentUserProvider,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            @Value("${ledger.idempotency.required:true}") boolean enforceRequiredGlobally
    ) {
        this.idempotencyService = idempotencyService;
        this.currentUserProvider = currentUserProvider;
        this.canonicalMapper = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.enforceRequiredGlobally = enforceRequiredGlobally;
    }

    @Around("@annotation(idempotent)")
    public Object enforceIdempotency(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return joinPoint.proceed();
        }

        HttpServletRequest request = attrs.getRequest();
        String rawKey = request.getHeader(HEADER_NAME);

        if (!StringUtils.hasText(rawKey)) {
            if (enforceRequiredGlobally && idempotent.required()) {
                throw new BusinessException("MISSING_IDEMPOTENCY_KEY", "Idempotency-Key header is required");
            }
            return joinPoint.proceed();
        }

        String key = rawKey.trim();
        if (key.length() > MAX_KEY_LENGTH) {
            throw new BusinessException(
                    "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be at most " + MAX_KEY_LENGTH + " characters"
            );
        }

        Long organizationId = currentUserProvider.getCurrentOrganizationId();
        String endpoint = request.getMethod() + " " + request.getRequestURI();
        String requestHash = computeRequestHash(endpoint, joinPoint);

        IdempotencyService.ClaimOutcome outcome =
                idempotencyService.claimOrLoad(organizationId, key, endpoint, requestHash);

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Class<?> returnType = signature.getReturnType();

        if (outcome instanceof IdempotencyService.ClaimOutcome.Replay replay) {
            if (replay.responseBody() == null) {
                return ResponseEntity.class.isAssignableFrom(returnType)
                        ? ResponseEntity.status(replay.statusCode()).build()
                        : null;
            }
            Object body = canonicalMapper.readValue(replay.responseBody(), returnType);
            if (ResponseEntity.class.isAssignableFrom(returnType)) {
                return ResponseEntity.status(replay.statusCode()).body(body);
            }
            return body;
        }

        IdempotencyService.ClaimOutcome.Acquired acquired = (IdempotencyService.ClaimOutcome.Acquired) outcome;
        try {
            return transactionTemplate.execute(status -> {
                try {
                    Object result = joinPoint.proceed();
                    Object payloadToSerialize = result instanceof ResponseEntity<?> entity
                            ? entity.getBody()
                            : result;
                    int statusCode = result instanceof ResponseEntity<?> entity
                            ? entity.getStatusCode().value()
                            : 200;
                    String json = payloadToSerialize == null
                            ? null
                            : canonicalMapper.writeValueAsString(payloadToSerialize);
                    idempotencyService.markCompleted(
                            acquired.recordId(),
                            acquired.claimLockedAt(),
                            statusCode,
                            json
                    );
                    return result;
                } catch (RuntimeException | Error ex) {
                    throw ex;
                } catch (Throwable ex) {
                    throw new SneakyException(ex);
                }
            });
        } catch (SneakyException wrapped) {
            idempotencyService.markFailed(acquired.recordId(), acquired.claimLockedAt());
            throw wrapped.getCause();
        } catch (Throwable ex) {
            idempotencyService.markFailed(acquired.recordId(), acquired.claimLockedAt());
            throw ex;
        }
    }

    private String computeRequestHash(String endpoint, ProceedingJoinPoint joinPoint) throws Exception {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Annotation[][] paramAnnotations = signature.getMethod().getParameterAnnotations();
        Object[] args = joinPoint.getArgs();

        Object bodyArg = null;
        for (int i = 0; i < paramAnnotations.length; i++) {
            for (Annotation annotation : paramAnnotations[i]) {
                if (annotation instanceof RequestBody) {
                    bodyArg = args[i];
                    break;
                }
            }
        }

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("endpoint", endpoint);
        envelope.put("body", bodyArg);

        byte[] bytes = canonicalMapper.writeValueAsBytes(envelope);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        return HexFormat.of().formatHex(digest);
    }

    private static final class SneakyException extends RuntimeException {
        SneakyException(Throwable cause) {
            super(cause);
        }
    }
}
