package com.alibaba.spring.boot.rsocket.broker.route.impl;

import com.alibaba.rsocket.ServiceLocator;
import com.alibaba.rsocket.utils.MurmurHash3;
import com.alibaba.spring.boot.rsocket.broker.route.ServiceRoutingSelector;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.multimap.set.UnifiedSetMultimap;
import org.jetbrains.annotations.Nullable;
import reactor.core.publisher.Sinks;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * service routine selector implementation
 *
 * @author leijuan
 */
public class ServiceRoutingSelectorImpl implements ServiceRoutingSelector {
    /**
     * service to handlers
     */
    private FastListMultimap2<Integer, Integer> serviceHandlers = new FastListMultimap2<>();
    /**
     * distinct Services
     */
    private IntObjectHashMap<ServiceLocator> distinctServices = new IntObjectHashMap<>();

    private FastListMultimap2<String, Integer> p2pServiceConsumers = new FastListMultimap2<>();

    /**
     * instance to services
     */
    private UnifiedSetMultimap<Integer, Integer> instanceServices = new UnifiedSetMultimap<>();
    private Sinks.Many<String> p2pServiceNotificationProcessor;

    public ServiceRoutingSelectorImpl(Sinks.Many<String> p2pServiceNotificationProcessor) {
        this.p2pServiceNotificationProcessor = p2pServiceNotificationProcessor;
    }

    @Override
    public void register(Integer instanceId, Set<ServiceLocator> services) {
        register(instanceId, 1, services);
    }

    @Override
    public void register(Integer instanceId, int powerUnit, Set<ServiceLocator> services) {
        //todo notification for global service
        for (ServiceLocator serviceLocator : services) {
            int serviceId = serviceLocator.getId();
            if (!instanceServices.get(instanceId).contains(serviceId)) {
                instanceServices.put(instanceId, serviceId);
                serviceHandlers.putMultiCopies(serviceId, instanceId, powerUnit);
                distinctServices.put(serviceId, serviceLocator);
                //p2p service notification
                if (p2pServiceConsumers.containsKey(serviceLocator.getGsv())) {
                    p2pServiceNotificationProcessor.tryEmitNext(serviceLocator.getGsv());
                }
            }
        }
    }

    public Set<Integer> findServicesByInstance(Integer instanceId) {
        return instanceServices.get(instanceId);
    }

    @Override
    public void deregister(Integer instanceId) {
        if (instanceServices.containsKey(instanceId)) {
            for (Integer serviceId : instanceServices.get(instanceId)) {
                ServiceLocator serviceLocator = distinctServices.get(serviceId);
                serviceHandlers.removeAllSameValue(serviceId, instanceId);
                if (!serviceHandlers.containsKey(serviceId)) {
                    distinctServices.remove(serviceId);
                }
                if (serviceLocator != null && p2pServiceConsumers.containsKey(serviceLocator.getGsv())) {
                    p2pServiceNotificationProcessor.tryEmitNext(serviceLocator.getGsv());
                }
            }
            instanceServices.removeAll(instanceId);
        }
    }

    public void deregister(Integer instanceId, Integer serviceId) {
        if (instanceServices.containsKey(instanceId)) {
            serviceHandlers.removeAllSameValue(serviceId, instanceId);
            if (!serviceHandlers.containsKey(serviceId)) {
                distinctServices.remove(serviceId);
            }
            instanceServices.remove(instanceId, serviceId);
            if (!instanceServices.containsKey(instanceId)) {
                instanceServices.removeAll(instanceId);
            }
        }
    }

    @Override
    public void registerP2pServiceConsumer(Integer instanceId, List<String> p2pServices) {
        for (String p2pService : p2pServices) {
            p2pServiceConsumers.put(p2pService, instanceId);
        }
    }

    @Override
    public void unRegisterP2pServiceConsumer(Integer instanceId, List<String> p2pServices) {
        for (String p2pService : p2pServices) {
            p2pServiceConsumers.remove(p2pService, instanceId);
        }
    }

    @Override
    public List<Integer> findP2pServiceConsumers(String p2pService) {
        return p2pServiceConsumers.get(p2pService);
    }

    @Override
    public boolean containInstance(Integer instanceId) {
        return instanceServices.containsKey(instanceId);
    }

    @Override
    public boolean containService(Integer serviceId) {
        return serviceHandlers.containsKey(serviceId);
    }

    @Override
    public @Nullable ServiceLocator findServiceById(Integer serviceId) {
        return distinctServices.get(serviceId);
    }

    @Nullable
    @Override
    public Integer findHandler(Integer serviceId) {
        MutableList<Integer> handlers = serviceHandlers.get(serviceId);
        int handlerCount = handlers.size();
        if (handlerCount > 1) {
            try {
                return handlers.get(ThreadLocalRandom.current().nextInt(handlerCount));
            } catch (Exception e) {
                return handlers.get(0);
            }
        } else if (handlerCount == 1) {
            return handlers.get(0);
        } else {
            return null;
        }
    }

    @Override
    public Collection<Integer> findHandlers(Integer serviceId) {
        return serviceHandlers.get(serviceId);

    }

    @Override
    public Integer getInstanceCount(Integer serviceId) {
        MutableList<Integer> handlerCount = serviceHandlers.get(serviceId);
        if (handlerCount.size() <= 1) {
            return handlerCount.size();
        } else {
            return handlerCount.distinct().size();
        }
    }

    @Override
    public Integer getInstanceCount(String serviceName) {
        return getInstanceCount(MurmurHash3.hash32(serviceName));
    }

    @Override
    public Collection<ServiceLocator> findAllServices() {
        return distinctServices.values();
    }

    @Override
    public int getDistinctServiceCount() {
        return distinctServices.keySet().size();
    }
}
