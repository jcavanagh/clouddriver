/*
 * Copyright 2017 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.netflix.spinnaker.clouddriver.kubernetes.caching.view.model;

import com.google.common.collect.ImmutableMap;
import com.netflix.spinnaker.cats.cache.CacheData;
import com.netflix.spinnaker.clouddriver.kubernetes.KubernetesCloudProvider;
import com.netflix.spinnaker.clouddriver.kubernetes.caching.Keys;
import com.netflix.spinnaker.clouddriver.kubernetes.caching.agent.KubernetesCacheDataConverter;
import com.netflix.spinnaker.clouddriver.kubernetes.description.manifest.KubernetesApiVersion;
import com.netflix.spinnaker.clouddriver.kubernetes.description.manifest.KubernetesKind;
import com.netflix.spinnaker.clouddriver.kubernetes.description.manifest.KubernetesManifest;
import com.netflix.spinnaker.clouddriver.kubernetes.provider.KubernetesModelUtil;
import com.netflix.spinnaker.clouddriver.model.HealthState;
import com.netflix.spinnaker.clouddriver.model.Instance;
import com.netflix.spinnaker.clouddriver.model.LoadBalancerInstance;
import com.netflix.spinnaker.clouddriver.names.NamerRegistry;
import com.netflix.spinnaker.moniker.Moniker;
import io.kubernetes.client.openapi.models.V1PodStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.validation.constraints.Null;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Value
public final class KubernetesV2Instance implements Instance, KubernetesResource {
  private final List<Map<String, Object>> health;
  private final String account;
  // An implementor of the Instance interface is implicitly expected to return a globally-unique ID
  // as its name because InstanceViewModel serializes it as such for API responses and Deck then
  // relies on it to disambiguate between instances.
  private final String name;
  private final String humanReadableName;
  private final String namespace;
  private final String displayName;
  private final KubernetesApiVersion apiVersion;
  private final KubernetesKind kind;
  private final Map<String, String> labels;
  private final Moniker moniker;

  @Null
  @Override
  public Long getLaunchTime() {
    return null;
  }

  private KubernetesV2Instance(KubernetesManifest manifest, String key) {
    this.account = ((Keys.InfrastructureCacheKey) Keys.parseKey(key).get()).getAccount();
    this.name = manifest.getUid();
    this.humanReadableName = manifest.getFullResourceName();
    this.namespace = manifest.getNamespace();
    this.displayName = manifest.getName();
    this.apiVersion = manifest.getApiVersion();
    this.kind = manifest.getKind();
    this.labels = ImmutableMap.copyOf(manifest.getLabels());
    this.moniker =
        NamerRegistry.lookup()
            .withProvider(KubernetesCloudProvider.ID)
            .withAccount(account)
            .withResource(KubernetesManifest.class)
            .deriveMoniker(manifest);

    this.health = new ArrayList<>();
    V1PodStatus status =
        KubernetesCacheDataConverter.getResource(manifest.getStatus(), V1PodStatus.class);
    if (status != null) {
      health.add(new KubernetesV2Health(status).toMap());
      if (status.getContainerStatuses() != null) {
        health.addAll(
            status.getContainerStatuses().stream()
                .map(KubernetesV2Health::new)
                .map(KubernetesV2Health::toMap)
                .collect(Collectors.toList()));
      }
    }
  }

  public static KubernetesV2Instance fromCacheData(CacheData cd) {
    if (cd == null) {
      return null;
    }

    KubernetesManifest manifest = KubernetesCacheDataConverter.getManifest(cd);

    if (manifest == null) {
      log.warn("Cache data {} inserted without a manifest", cd.getId());
      return null;
    }

    return new KubernetesV2Instance(manifest, cd.getId());
  }

  public LoadBalancerInstance toLoadBalancerInstance() {
    return LoadBalancerInstance.builder()
        .health(
            health.stream()
                .reduce(
                    new HashMap<>(),
                    (a, b) -> {
                      Map<String, Object> result = new HashMap<>();
                      result.putAll(a);
                      result.putAll(b);
                      return result;
                    }))
        .id(getName())
        .zone(getZone())
        .name(getHumanReadableName())
        .build();
  }

  @Override
  public HealthState getHealthState() {
    return KubernetesModelUtil.getHealthState(health);
  }

  @Override
  public String getZone() {
    return namespace;
  }

  @Override
  public String getProviderType() {
    return KubernetesCloudProvider.ID;
  }

  @Override
  public String getCloudProvider() {
    return KubernetesCloudProvider.ID;
  }
}
