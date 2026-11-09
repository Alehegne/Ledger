package com.example.Ledger.common.debug;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.lang.reflect.Type;

@ControllerAdvice
public class RequestDebugAdvice extends RequestBodyAdviceAdapter {

    private static final Logger log = LoggerFactory.getLogger(RequestDebugAdvice.class);

    @Override
    public boolean supports(
            MethodParameter methodParameter,
            Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType
    ) {
        return log.isDebugEnabled();
    }

    @Override
    public Object afterBodyRead(
            Object body,
            HttpInputMessage inputMessage,
            MethodParameter parameter,
            Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType
    ) {
        if (log.isDebugEnabled() && parameter.getMethod() != null) {
            log.debug(
                    "Deserialized request payload of type {} for {}.{}",
                    body != null ? body.getClass().getSimpleName() : "null",
                    parameter.getDeclaringClass().getSimpleName(),
                    parameter.getMethod().getName()
            );
        }
        return body;
    }
}
