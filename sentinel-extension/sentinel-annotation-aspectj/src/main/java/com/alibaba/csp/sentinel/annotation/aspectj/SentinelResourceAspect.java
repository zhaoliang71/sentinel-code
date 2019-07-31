/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.annotation.aspectj;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;

import java.lang.reflect.Method;

/**
 * Aspect for methods with {@link SentinelResource} annotation.
 *
 * @author Eric Zhao
 */
@Aspect
public class SentinelResourceAspect extends AbstractSentinelAspectSupport {

    @Pointcut("@annotation(com.alibaba.csp.sentinel.annotation.SentinelResource)")
    public void sentinelResourceAnnotationPointcut() {
    }

    @Around("sentinelResourceAnnotationPointcut()")
    public Object invokeResourceWithSentinel(ProceedingJoinPoint pjp) throws Throwable {
        //获取当前资源方法
        Method originMethod = resolveMethod(pjp);
        //获取资源注解配置
        SentinelResource annotation = originMethod.getAnnotation(SentinelResource.class);
        if (annotation == null) {
            // Should not go through here.
            throw new IllegalStateException("Wrong state for SentinelResource annotation");
        }
        //获取资源名
        String resourceName = getResourceName(annotation.value(), originMethod);
        EntryType entryType = annotation.entryType();
        Entry entry = null;
        try {
            //如果限流则抛出 FlowException 异常
            entry = SphU.entry(resourceName, entryType, 1, pjp.getArgs());
            //调用资源方法
            Object result = pjp.proceed();
            return result;
        } catch (BlockException ex) {
            //降级的handlerBlock方法
            return handleBlockException(pjp, annotation, ex);
        } catch (Throwable ex) {
            //获取配置的忽略异常，即改异常不会被降级，会直接抛出
            Class<? extends Throwable>[] exceptionsToIgnore = annotation.exceptionsToIgnore();
            //判断当前异常是否为exceptionsToIgnore中的异常或exceptionsToIgnore异常的子类，是的话直接抛出
            if (exceptionsToIgnore.length > 0 && exceptionBelongsTo(ex, exceptionsToIgnore)) {
                throw ex;
            }
            // annotation.exceptionsToTrace()  默认值Throwable.class
            // 非exceptionsToIgnore中,但在exceptionsToTrace中的异常才会进行fallback降级
            if (exceptionBelongsTo(ex, annotation.exceptionsToTrace())) {
                //统计异常信息
                traceException(ex, annotation);
                //fallback方法
                return handleFallback(pjp, annotation, ex);
            }

            // No fallback function can handle the exception, so throw it out.
            throw ex;
        } finally {
            if (entry != null) {
                entry.exit(1, pjp.getArgs());
            }
        }
    }
}
