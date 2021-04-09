package com.alibaba.spring.boot.rsocket.broker.cluster;

import com.alibaba.rsocket.ServiceLocator;
import com.alibaba.rsocket.cloudevents.CloudEventImpl;
import com.alibaba.rsocket.transport.NetworkUtil;
import io.scalecube.cluster.ClusterMessageHandler;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import reactor.core.Disposable;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * RSocket Broker Manager Reactive Discovery client implementation
 *
 * @author leijuan
 */
public class RSocketBrokerManagerDiscoveryImpl implements RSocketBrokerManager, ClusterMessageHandler, DisposableBean {
    private ReactiveDiscoveryClient discoveryClient;
    private Map<String, RSocketBroker> currentBrokers = new HashMap<>();
    private final String SERVICE_NAME = "rsocket-broker";
    private EmitterProcessor<Collection<RSocketBroker>> brokersEmitterProcessor = EmitterProcessor.create();
    private Disposable brokersFresher;

    public RSocketBrokerManagerDiscoveryImpl(ReactiveDiscoveryClient discoveryClient) {
        this.discoveryClient = discoveryClient;
        Disposable brokersFresher = Flux.interval(Duration.ofSeconds(10)).flatMap(aLong -> {
            return this.discoveryClient.getInstances(SERVICE_NAME);
        }).collectList().subscribe(serviceInstances -> {
            boolean changed = serviceInstances.size() != currentBrokers.size();
            for (ServiceInstance serviceInstance : serviceInstances) {
                if (!currentBrokers.containsKey(serviceInstance.getHost())) {
                    changed = true;
                }
            }
            if (changed) {
                currentBrokers = serviceInstances.stream().map(serviceInstance -> {
                    RSocketBroker broker = new RSocketBroker();
                    broker.setIp(serviceInstance.getHost());
                    return broker;
                }).collect(Collectors.toMap(RSocketBroker::getIp, Function.identity()));
                brokersEmitterProcessor.onNext(currentBrokers.values());
            }
        });
    }

    @Override
    public Flux<Collection<RSocketBroker>> requestAll() {
        return brokersEmitterProcessor;
    }

    @Override
    public RSocketBroker localBroker() {
        return currentBrokers.get(NetworkUtil.LOCAL_IP);
    }

    @Override
    public Collection<RSocketBroker> currentBrokers() {
        return currentBrokers.values();
    }

    @Override
    public Mono<RSocketBroker> findByIp(String ip) {
        if (currentBrokers.containsKey(ip)) {
            return Mono.empty();
        } else {
            return Mono.just(currentBrokers.get(ip));
        }
    }

    @Override
    public Flux<ServiceLocator> findServices(String ip) {
        return Flux.empty();
    }

    @Override
    public Boolean isStandAlone() {
        return false;
    }

    @Override
    public void stopLocalBroker() {
        this.brokersFresher.dispose();
    }

    @Override
    public Mono<String> broadcast(CloudEventImpl<?> cloudEvent) {
        return Mono.empty();
    }

    @Override
    public RSocketBroker findConsistentBroker(String clientId) {
        return null;
    }

    @Override
    public void destroy() throws Exception {

    }
}
