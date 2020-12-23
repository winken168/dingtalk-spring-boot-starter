/*
 * Copyright ©2015-2020 Jaemon. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dingerframework.multi;

import com.dingerframework.core.DingerConfig;
import com.dingerframework.entity.enums.ExceptionEnum;
import com.dingerframework.exception.DingerException;
import com.dingerframework.multi.algorithm.AlgorithmHandler;
import com.dingerframework.multi.entity.MultiDingerAlgorithmDefinition;
import com.dingerframework.multi.entity.MultiDingerConfig;
import com.dingerframework.utils.DingerUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.lang.reflect.Field;
import java.util.*;

/**
 * MultiDingerAlgorithmRegister
 *
 * <p>-------</p>
 * <h3>Application InitializingBean</h3>
 * <pre>
 *     {@code @Component}
 *     {@code @DependsOn(AlgorithmHandler.MULTI_DINGER_PRIORITY_EXECUTE)}
 *     <span style="color:green">public class ServiceInit implements InitializingBean {</span>
 *         {@code @Override}
 *         public void afterPropertiesSet() throws Exception {
 *             // ...
 *         }
 *     <span style="color:green">}</span>
 * </pre>
 *
 * @author Jaemon
 * @since 3.0
 */
public class MultiDingerAlgorithmRegister implements ApplicationContextAware, InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(MultiDingerAlgorithmRegister.class);
    private static ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        if (MultiDingerAlgorithmRegister.applicationContext == null) {
            MultiDingerAlgorithmRegister.applicationContext = applicationContext;
        } else {
            log.warn("applicationContext is not null.");
        }
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        boolean debugEnabled = log.isDebugEnabled();

        if (MultiDingerScannerRegistrar.MULTIDINGER_ALGORITHM_DEFINITION_MAP.isEmpty()) {
            // 当前算法处理容器为空, MultiDinger失效。 可能由于所有的算法处理器中无注入属性信息
            log.info("AlgorithmHandler Container is Empty.");
            return;
        }

        Set<Map.Entry<String, MultiDingerAlgorithmDefinition>> entries = MultiDingerScannerRegistrar.MULTIDINGER_ALGORITHM_DEFINITION_MAP.entrySet();
        for (Map.Entry<String, MultiDingerAlgorithmDefinition> entry : entries) {
            //  v.key + SPOT_SEPERATOR + AlgorithmHandler.getSimpleName
            String beanName = entry.getKey();
            MultiDingerAlgorithmDefinition v = entry.getValue();
            Class<? extends AlgorithmHandler> algorithm = v.getAlgorithm();
            // 从spring容器中拿到算法处理对象
            AlgorithmHandler algorithmHandler = applicationContext.getBean(beanName, algorithm);
            Field[] declaredFields = algorithm.getDeclaredFields();
            // 字段对象注入
            OK:
            for (Field declaredField : declaredFields) {
                if (declaredField.isAnnotationPresent(Autowired.class)) {
                    String fieldBeanName = declaredField.getName();
                    if (declaredField.isAnnotationPresent(Qualifier.class)) {
                        Qualifier qualifier = declaredField.getAnnotation(Qualifier.class);
                        if (DingerUtils.isNotEmpty(qualifier.value())) {
                            fieldBeanName = qualifier.value();
                        }
                    }

                    String[] actualBeanNames = applicationContext.getBeanNamesForType(declaredField.getType());
                    int length = actualBeanNames.length;
                    if (length == 0) {
                        if (debugEnabled) {
                            log.debug("algorithm={}'s field object {} can't be found from spring container.",
                                    algorithm.getSimpleName(), fieldBeanName);
                        }
                        continue;
                    } else if (length == 1) {
                        fieldBeanName = actualBeanNames[0];
                    } else {
                        final String fbn = fieldBeanName;
                        long count = Arrays.stream(actualBeanNames).filter(e -> Objects.equals(e, fbn)).count();
                        if (count == 0) {
                            if (debugEnabled) {
                                log.debug("algorithm={}'s field object {} can't find a match from objects {}.",
                                        algorithm.getSimpleName(), fieldBeanName, Arrays.asList(actualBeanNames));
                            }
                            continue OK;
                        }
                    }

                    try {
                        declaredField.setAccessible(true);
                        declaredField.set(algorithmHandler, applicationContext.getBean(fieldBeanName));
                    } catch (IllegalAccessException e) {
                        log.warn("algorithm={}'s field={} injection failed, because of {}.",
                                algorithm.getSimpleName(), fieldBeanName, e.getMessage());
                        continue OK;
                    }

                }
            }

            List<DingerConfig> dingerConfigs = v.getDingerConfigs();
            // check dingerConfig is valid
            dingerConfigs.forEach(e -> {
                e.check();
                if (e.checkEmpty()) {
                    throw new DingerException(algorithm.getSimpleName() + " dingerConfigs配置异常", ExceptionEnum.MULTI_DINGERCONFIGS_EXCEPTION);
                }
            });
            // v.getKey() is dingerClassName or MultiDingerConfigContainer#GLOABL_KEY
            MultiDingerConfigContainer.INSTANCE.put(
                    v.getKey(), new MultiDingerConfig(algorithmHandler, dingerConfigs)
            );
            if (debugEnabled) {
                log.debug("multiDinger key(dingerClassName)={}, algorithmHandler class={}, dingerConfigs={}.",
                        v.getKey(), algorithm.getSimpleName(), dingerConfigs.size());
            }
        }

        MultiDingerScannerRegistrar.MULTIDINGER_ALGORITHM_DEFINITION_MAP.clear();
    }

    protected static void clear() {
        MultiDingerAlgorithmRegister.applicationContext = null;
    }

}