/*
 * Copyright 2016 Wultra s.r.o.
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

package io.getlime.push.configuration;

import io.getlime.push.service.batch.storage.AppCredentialStorageMap;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/**
 * Configuration class for in memory maps
 *
 * @author Martin Tupy, martin.tupy.work@gmail.com
 */
@Configuration
public class StorageMapConfiguration {

    /**
     * Bean definition for app credentials map
     * @return Bean with app credential storage map.
     */
    @Bean
    public AppCredentialStorageMap appCredentialStorageMap() {
        return new AppCredentialStorageMap();
    }
}
