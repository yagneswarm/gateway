package in.projecteka.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import in.projecteka.gateway.clients.ClientRegistryClient;
import in.projecteka.gateway.clients.ClientRegistryProperties;
import in.projecteka.gateway.clients.ConsentFetchServiceClient;
import in.projecteka.gateway.clients.ConsentRequestServiceClient;
import in.projecteka.gateway.clients.DataFlowRequestServiceClient;
import in.projecteka.gateway.clients.DiscoveryServiceClient;
import in.projecteka.gateway.clients.HipConsentNotifyServiceClient;
import in.projecteka.gateway.clients.HipDataFlowServiceClient;
import in.projecteka.gateway.clients.HiuConsentNotifyServiceClient;
import in.projecteka.gateway.clients.HealthInfoNotificationServiceClient;
import in.projecteka.gateway.clients.LinkConfirmServiceClient;
import in.projecteka.gateway.clients.LinkInitServiceClient;
import in.projecteka.gateway.clients.PatientSearchServiceClient;
import in.projecteka.gateway.common.CentralRegistry;
import in.projecteka.gateway.common.DefaultValidatedRequestAction;
import in.projecteka.gateway.common.DefaultValidatedResponseAction;
import in.projecteka.gateway.common.RequestOrchestrator;
import in.projecteka.gateway.common.ResponseOrchestrator;
import in.projecteka.gateway.common.RetryableValidatedRequestAction;
import in.projecteka.gateway.common.RetryableValidatedResponseAction;
import in.projecteka.gateway.common.Validator;
import in.projecteka.gateway.common.cache.CacheAdapter;
import in.projecteka.gateway.common.cache.LoadingCacheAdapter;
import in.projecteka.gateway.common.cache.RedisCacheAdapter;
import in.projecteka.gateway.common.cache.RedisOptions;
import in.projecteka.gateway.common.cache.ServiceOptions;
import in.projecteka.gateway.registry.BridgeRegistry;
import in.projecteka.gateway.registry.CMRegistry;
import in.projecteka.gateway.registry.YamlRegistry;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static in.projecteka.gateway.common.Constants.GW_DATAFLOW_QUEUE;
import static in.projecteka.gateway.common.Constants.GW_LINK_QUEUE;
import static in.projecteka.gateway.common.Constants.X_HIP_ID;

@Configuration
public class GatewayConfiguration {
    @ConditionalOnProperty(value = "gateway.cacheMethod", havingValue = "redis")
    @Bean({"requestIdMappings"})
    public CacheAdapter<String, String> createRedisCacheAdapter(RedisOptions redisOptions) {
        RedisClient redisClient = getRedisClient(redisOptions);
        return new RedisCacheAdapter(redisClient, 5);
    }

    private RedisClient getRedisClient(RedisOptions redisOptions) {
        RedisURI redisUri = RedisURI.Builder.
                redis(redisOptions.getHost())
                .withPort(redisOptions.getPort())
                .withPassword(redisOptions.getPassword())
                .build();
        return RedisClient.create(redisUri);
    }

    @ConditionalOnProperty(value = "guava.cacheMethod", havingValue = "guava", matchIfMissing = true)
    @Bean({"requestIdMappings"})
    public CacheAdapter<String, String> createLoadingCacheAdapter() {
        return new LoadingCacheAdapter(createSessionCache(5));
    }

    public LoadingCache<String, String> createSessionCache(int duration) {
        return CacheBuilder
                .newBuilder()
                .expireAfterWrite(duration, TimeUnit.MINUTES)
                .build(new CacheLoader<String, String>() {
                    public String load(String key) {
                        return "";
                    }
                });
    }

    @Bean
    public YamlRegistry createYamlRegistry(ServiceOptions serviceOptions) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
        return objectMapper.readValue(new File(serviceOptions.getRegistryPath()), YamlRegistry.class);
    }

    @Bean
    public CMRegistry cmRegistry(YamlRegistry yamlRegistry) {
        return new CMRegistry(yamlRegistry);
    }

    @Bean
    public BridgeRegistry bridgeRegistry(YamlRegistry yamlRegistry) {
        return new BridgeRegistry(yamlRegistry);
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean("discoveryServiceClient")
    public DiscoveryServiceClient discoveryServiceClient(ServiceOptions serviceOptions,
                                                         WebClient.Builder builder,
                                                         CMRegistry cmRegistry,
                                                         CentralRegistry centralRegistry,
                                                         BridgeRegistry bridgeRegistry) {
        return new DiscoveryServiceClient(serviceOptions, builder, centralRegistry, cmRegistry, bridgeRegistry);
    }

    @Bean("discoveryRequestAction")
    public DefaultValidatedRequestAction<DiscoveryServiceClient> discoveryRequestAction(
            DiscoveryServiceClient discoveryServiceClient) {
        return new DefaultValidatedRequestAction<>(discoveryServiceClient);
    }

    @Bean("discoveryRequestOrchestrator")
    public RequestOrchestrator<DiscoveryServiceClient> discoveryHelper(
            CacheAdapter<String, String> requestIdMappings,
            Validator validator,
            DiscoveryServiceClient discoveryServiceClient,
            DefaultValidatedRequestAction<DiscoveryServiceClient> discoveryRequestAction) {
        return new RequestOrchestrator<>(requestIdMappings, validator, discoveryServiceClient,discoveryRequestAction);
    }

    @Bean("discoveryResponseAction")
    public DefaultValidatedResponseAction<DiscoveryServiceClient> discoveryResponseAction(
            DiscoveryServiceClient discoveryServiceClient) {
        return new DefaultValidatedResponseAction<>(discoveryServiceClient);
    }

    @Bean("discoveryResponseOrchestrator")
    public ResponseOrchestrator discoveryResponseOrchestrator(
            Validator validator,
            DefaultValidatedResponseAction<DiscoveryServiceClient> discoveryResponseAction) {
        return new ResponseOrchestrator(validator, discoveryResponseAction);
    }

    @Bean
    public Validator validator(BridgeRegistry bridgeRegistry,
                               CMRegistry cmRegistry,
                               CacheAdapter<String, String> requestIdMappings) {
        return new Validator(bridgeRegistry, cmRegistry, requestIdMappings);
    }

    @Bean("linkInitServiceClient")
    public LinkInitServiceClient linkInitServiceClient(ServiceOptions serviceOptions,
                                                       WebClient.Builder builder,
                                                       CMRegistry cmRegistry,
                                                       CentralRegistry centralRegistry,
                                                       BridgeRegistry bridgeRegistry) {
        return new LinkInitServiceClient(builder, serviceOptions, centralRegistry, cmRegistry, bridgeRegistry);
    }

    @Bean("linkInitRequestAction")
    public DefaultValidatedRequestAction<LinkInitServiceClient> linkInitRequestAction(
            LinkInitServiceClient linkInitServiceClient) {
        return new DefaultValidatedRequestAction<>(linkInitServiceClient);
    }

    @Bean("linkInitRequestOrchestrator")
    public RequestOrchestrator<LinkInitServiceClient> linkInitRequestOrchestrator(
            CacheAdapter<String, String> requestIdMappings,
            Validator validator,
            LinkInitServiceClient linkInitServiceClient,
            DefaultValidatedRequestAction<LinkInitServiceClient> linkInitRequestAction) {
        return new RequestOrchestrator<>(requestIdMappings, validator, linkInitServiceClient, linkInitRequestAction);
    }

    @Bean("linkInitResponseAction")
    public DefaultValidatedResponseAction<LinkInitServiceClient> linkInitResponseAction(
            LinkInitServiceClient linkInitServiceClient) {
        return new DefaultValidatedResponseAction<>(linkInitServiceClient);
    }

    @Bean("linkInitResponseOrchestrator")
    public ResponseOrchestrator linkInitResponseOrchestrator(
            Validator validator,
            DefaultValidatedResponseAction<LinkInitServiceClient> linkInitResponseAction) {
        return new ResponseOrchestrator(validator, linkInitResponseAction);
    }

    @Bean
    public LinkConfirmServiceClient linkConfirmServiceClient(ServiceOptions serviceOptions,
                                                             WebClient.Builder builder,
                                                             CMRegistry cmRegistry,
                                                             CentralRegistry centralRegistry,
                                                             BridgeRegistry bridgeRegistry) {
        return new LinkConfirmServiceClient(builder, serviceOptions, centralRegistry, cmRegistry, bridgeRegistry);
    }

    @Bean("linkConfirmRequestAction")
    public DefaultValidatedRequestAction<LinkConfirmServiceClient> linkConfirmRequestAction(
            LinkConfirmServiceClient linkConfirmServiceClient) {
        return new DefaultValidatedRequestAction<>(linkConfirmServiceClient);
    }

    @Bean("linkConfirmRequestOrchestrator")
    public RequestOrchestrator<LinkConfirmServiceClient> linkConfirmRequestOrchestrator(
            CacheAdapter<String, String> requestIdMappings,
            Validator validator,
            LinkConfirmServiceClient linkConfirmServiceClient,
            DefaultValidatedRequestAction<LinkConfirmServiceClient> linkConfirmRequestAction) {
        return new RequestOrchestrator<>(requestIdMappings, validator, linkConfirmServiceClient, linkConfirmRequestAction);
    }

    @Bean("linkConfirmResponseAction")
    public DefaultValidatedResponseAction<LinkConfirmServiceClient> linkConfirmResponseAction(
            LinkConfirmServiceClient linkConfirmServiceClient) {
        return new DefaultValidatedResponseAction<>(linkConfirmServiceClient);
    }

    @Bean
    public Jackson2JsonMessageConverter converter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean("retryableLinkConfirmResponseAction")
    public RetryableValidatedResponseAction<LinkConfirmServiceClient> retryableLinkResponseAction(
            DefaultValidatedResponseAction<LinkConfirmServiceClient> linkConfirmResponseAction,
            AmqpTemplate amqpTemplate,
            Jackson2JsonMessageConverter converter,
            ServiceOptions serviceOptions) {
        return new RetryableValidatedResponseAction<>(amqpTemplate,
                converter,
                linkConfirmResponseAction,
                serviceOptions,
                GW_LINK_QUEUE);
    }


    @Bean("linkConfirmResponseOrchestrator")
    public ResponseOrchestrator linkConfirmResponseOrchestrator(
            Validator validator,
            RetryableValidatedResponseAction<LinkConfirmServiceClient> retryableLinkConfirmResponseAction) {
        return new ResponseOrchestrator(validator, retryableLinkConfirmResponseAction);
    }

    @Bean
    public ConsentRequestServiceClient consentRequestServiceClient(ServiceOptions serviceOptions,
                                                                   WebClient.Builder builder,
                                                                   BridgeRegistry bridgeRegistry,
                                                                   CentralRegistry centralRegistry,
                                                                   CMRegistry cmRegistry) {
        return new ConsentRequestServiceClient(serviceOptions, builder, centralRegistry, bridgeRegistry, cmRegistry);
    }

    @Bean
    public ConsentFetchServiceClient consentFetchServiceClient(ServiceOptions serviceOptions,
                                                               WebClient.Builder builder,
                                                               BridgeRegistry bridgeRegistry,
                                                               CentralRegistry centralRegistry,
                                                               CMRegistry cmRegistry) {
        return new ConsentFetchServiceClient(serviceOptions, builder, centralRegistry, bridgeRegistry, cmRegistry);
    }

    @Bean("consentRequestAction")
    public DefaultValidatedRequestAction<ConsentRequestServiceClient> consentRequestAction(
            ConsentRequestServiceClient consentRequestServiceClient) {
        return new DefaultValidatedRequestAction<>(consentRequestServiceClient);
    }


    @Bean("consentRequestOrchestrator")
    public RequestOrchestrator<ConsentRequestServiceClient> consentRequestOrchestrator(
            CacheAdapter<String, String> requestIdMappings,
            Validator validator,
            ConsentRequestServiceClient consentRequestServiceClient,
            DefaultValidatedRequestAction<ConsentRequestServiceClient> consentRequestAction) {
        return new RequestOrchestrator<>(requestIdMappings, validator, consentRequestServiceClient,consentRequestAction);
    }

    @Bean("consentFetchRequestAction")
    public DefaultValidatedRequestAction<ConsentFetchServiceClient> consentFetchRequestAction(
            ConsentFetchServiceClient consentFetchServiceClient) {
        return new DefaultValidatedRequestAction<>(consentFetchServiceClient);
    }

    @Bean("consentFetchRequestOrchestrator")
    public RequestOrchestrator<ConsentFetchServiceClient> consentFetchOrchestrator(
            CacheAdapter<String, String> requestIdMappings,
            Validator validator,
            ConsentFetchServiceClient consentFetchServiceClient,
            DefaultValidatedRequestAction<ConsentFetchServiceClient> consentRequestAction) {
        return new RequestOrchestrator<>(requestIdMappings, validator, consentFetchServiceClient,consentRequestAction);
    }

    @Bean("consentFetchResponseAction")
    public DefaultValidatedResponseAction<ConsentFetchServiceClient> consentFetchResponseAction(
            ConsentFetchServiceClient consentFetchServiceClient) {
        return new DefaultValidatedResponseAction<>(consentFetchServiceClient);
    }

    @Bean("consentFetchResponseOrchestrator")
    public ResponseOrchestrator consentFetchResponseOrchestrator(
            Validator validator,
            DefaultValidatedResponseAction<ConsentFetchServiceClient> consentFetchResponseAction) {
        return new ResponseOrchestrator(validator, consentFetchResponseAction);
    }

    @Bean("patientSearchRequestAction")
    public DefaultValidatedRequestAction<PatientSearchServiceClient> patientSearchRequestAction(
            PatientSearchServiceClient patientSearchServiceClient) {
        return new DefaultValidatedRequestAction<>(patientSearchServiceClient);
    }

    @Bean("patientSearchOrchestrator")
    public RequestOrchestrator<PatientSearchServiceClient> patientSearchOrchestrator(
            CacheAdapter<String, String> requestIdMappings,
            Validator validator,
            PatientSearchServiceClient patientSearchServiceClient,
            DefaultValidatedRequestAction<PatientSearchServiceClient> patientSearchRequestAction) {
        return new RequestOrchestrator<>(requestIdMappings, validator, patientSearchServiceClient, patientSearchRequestAction);
    }

    @Bean
    public ClientRegistryClient clientRegistryClient(WebClient.Builder builder,
                                                     ClientRegistryProperties clientRegistryProperties) {
        return new ClientRegistryClient(builder, clientRegistryProperties.getUrl());
    }

    @Bean
    public CentralRegistry centralRegistry(ClientRegistryProperties clientRegistryProperties,
                                           ClientRegistryClient clientRegistryClient) {
        return new CentralRegistry(clientRegistryClient, clientRegistryProperties);
    }

    @Bean
    public HipConsentNotifyServiceClient hipConsentNotifyServiceClient(ServiceOptions serviceOptions,
                                                                       WebClient.Builder builder,
                                                                       CentralRegistry centralRegistry,
                                                                       CMRegistry cmRegistry,
                                                                       BridgeRegistry bridgeRegistry) {
        return new HipConsentNotifyServiceClient(serviceOptions, builder, centralRegistry, cmRegistry, bridgeRegistry);
    }

    @Bean("hipConsentNotifyRequestAction")
    public DefaultValidatedRequestAction<HipConsentNotifyServiceClient> hipConsentNotifyRequestAction(
            HipConsentNotifyServiceClient hipConsentNotifyServiceClient) {
        return new DefaultValidatedRequestAction<>(hipConsentNotifyServiceClient);
    }

    @Bean("hipConsentNotifyRequestOrchestrator")
    public RequestOrchestrator<HipConsentNotifyServiceClient> hipConsentNotifyRequestOrchestrator(
            CacheAdapter<String, String> requestIdMappings,
            Validator validator,
            HipConsentNotifyServiceClient hipConsentNotifyServiceClient,
            DefaultValidatedRequestAction<HipConsentNotifyServiceClient> hipConsentNotifyRequestAction) {
        return new RequestOrchestrator<>(requestIdMappings, validator, hipConsentNotifyServiceClient, hipConsentNotifyRequestAction);
    }

    @Bean
    public HiuConsentNotifyServiceClient hiuConsentNotifyServiceClient(ServiceOptions serviceOptions,
                                                                       WebClient.Builder builder,
                                                                       CentralRegistry centralRegistry,
                                                                       CMRegistry cmRegistry,
                                                                       BridgeRegistry bridgeRegistry) {
        return new HiuConsentNotifyServiceClient(serviceOptions, builder, centralRegistry, cmRegistry, bridgeRegistry);
    }

    @Bean("hiuConsentNotifyRequestAction")
    public DefaultValidatedRequestAction<HiuConsentNotifyServiceClient> hiuConsentNotifyRequestAction(
            HiuConsentNotifyServiceClient hiuConsentNotifyServiceClient) {
        return new DefaultValidatedRequestAction<>(hiuConsentNotifyServiceClient);
    }

    @Bean("hiuConsentNotifyRequestOrchestrator")
    public RequestOrchestrator<HiuConsentNotifyServiceClient> hiuConsentNotifyRequestOrchestrator(
            CacheAdapter<String, String> requestIdMappings,
            Validator validator,
            HiuConsentNotifyServiceClient hiuConsentNotifyServiceClient,
            DefaultValidatedRequestAction<HiuConsentNotifyServiceClient> hiuConsentNotifyRequestAction) {
        return new RequestOrchestrator<>(requestIdMappings, validator, hiuConsentNotifyServiceClient,hiuConsentNotifyRequestAction);
    }

    @Bean("consentResponseAction")
    public DefaultValidatedResponseAction<ConsentRequestServiceClient> consentResponseAction(
            ConsentRequestServiceClient consentRequestServiceClient) {
        return new DefaultValidatedResponseAction<>(consentRequestServiceClient);
    }

    @Bean("consentResponseOrchestrator")
    public ResponseOrchestrator consentResponseOrchestrator(
            Validator validator,
            DefaultValidatedResponseAction<ConsentRequestServiceClient> consentResponseAction) {
        return new ResponseOrchestrator(validator, consentResponseAction);
    }

    @Bean
    public PatientSearchServiceClient patientSearchServiceClient(ServiceOptions serviceOptions,
                                                                 WebClient.Builder builder,
                                                                 CentralRegistry centralRegistry,
                                                                 BridgeRegistry bridgeRegistry,
                                                                 CMRegistry cmRegistry) {
        return new PatientSearchServiceClient(serviceOptions, builder, centralRegistry, bridgeRegistry, cmRegistry);
    }

    @Bean("patientSearchResponseAction")
    public DefaultValidatedResponseAction<PatientSearchServiceClient> patientSearchResponseAction(
            PatientSearchServiceClient patientSearchServiceClient) {
        return new DefaultValidatedResponseAction<>(patientSearchServiceClient);
    }

    @Bean("patientSearchResponseOrchestrator")
    public ResponseOrchestrator patientSearchResponseOrchestrator(
            Validator validator,
            DefaultValidatedResponseAction<PatientSearchServiceClient> patientSearchResponseAction) {
        return new ResponseOrchestrator(validator, patientSearchResponseAction);
    }

    @Bean
    public DataFlowRequestServiceClient dataFlowRequestServiceClient(ServiceOptions serviceOptions,
                                                                     WebClient.Builder builder,
                                                                     CentralRegistry centralRegistry,
                                                                     BridgeRegistry bridgeRegistry,
                                                                     CMRegistry cmRegistry) {
        return new DataFlowRequestServiceClient(serviceOptions, builder, centralRegistry, bridgeRegistry, cmRegistry);
    }

    @Bean("dataflowRequestAction")
    public DefaultValidatedRequestAction<DataFlowRequestServiceClient> dataflowRequestAction(
            DataFlowRequestServiceClient dataFlowRequestServiceClient) {
        return new DefaultValidatedRequestAction<>(dataFlowRequestServiceClient);
    }

    @Bean("dataFlowRequestOrchestrator")
    public RequestOrchestrator<DataFlowRequestServiceClient> dataFlowRequestOrchestrator(CacheAdapter<String, String> requestIdMappings,
                                                                                         Validator validator,
                                                                                         DataFlowRequestServiceClient dataFlowRequestServiceClient,
                                                                                         DefaultValidatedRequestAction<DataFlowRequestServiceClient> dataflowRequestAction) {
        return new RequestOrchestrator<>(requestIdMappings, validator, dataFlowRequestServiceClient, dataflowRequestAction);
    }

    @Bean("dataFlowRequestResponseAction")
    public DefaultValidatedResponseAction<DataFlowRequestServiceClient> dataFlowRequestResponseAction(
            DataFlowRequestServiceClient dataFlowRequestServiceClient) {
        return new DefaultValidatedResponseAction<>(dataFlowRequestServiceClient);
    }

    @Bean("dataFlowRequestResponseOrchestrator")
    public ResponseOrchestrator dataFlowRequestResponseOrchestrator(
            Validator validator,
            DefaultValidatedResponseAction<DataFlowRequestServiceClient> dataFlowRequestResponseAction) {
        return new ResponseOrchestrator(validator, dataFlowRequestResponseAction);
    }

    @Bean
    public HealthInfoNotificationServiceClient healthInformationRequestServiceClient(ServiceOptions serviceOptions,
                                                                                     WebClient.Builder builder,
                                                                                     CentralRegistry centralRegistry,
                                                                                     CMRegistry cmRegistry) {
        return new HealthInfoNotificationServiceClient(serviceOptions, builder, centralRegistry, cmRegistry);
    }

    @Bean("healthInfoNotificationRequestAction")
    public DefaultValidatedRequestAction<HealthInfoNotificationServiceClient> healthInfoNotificationRequestAction(
            HealthInfoNotificationServiceClient healthInfoNotificationServiceClient) {
        return new DefaultValidatedRequestAction<>(healthInfoNotificationServiceClient);
    }

    @Bean("healthInfoNotificationOrchestrator")
    public RequestOrchestrator<HealthInfoNotificationServiceClient> healthInfoNotificationOrchestrator(
            CacheAdapter<String, String> requestIdMappings,
            Validator validator,
            HealthInfoNotificationServiceClient healthInfoNotificationServiceClient,
            DefaultValidatedRequestAction<HealthInfoNotificationServiceClient> healthInfoNotificationRequestAction) {
        return new RequestOrchestrator<>(requestIdMappings, validator, healthInfoNotificationServiceClient,healthInfoNotificationRequestAction);
    }

    @Bean
    public HipDataFlowServiceClient hipDataFlowServiceClient(ServiceOptions serviceOptions,
                                                             WebClient.Builder builder,
                                                             CMRegistry cmRegistry,
                                                             CentralRegistry centralRegistry,
                                                             BridgeRegistry bridgeRegistry) {
        return new HipDataFlowServiceClient(serviceOptions, builder, centralRegistry, cmRegistry, bridgeRegistry);
    }

    @Bean("defalutHipDataflowRequestAction")
    public DefaultValidatedRequestAction<HipDataFlowServiceClient> defalutHipDataflowRequestAction(
            HipDataFlowServiceClient hipDataFlowServiceClient) {
        return new DefaultValidatedRequestAction<>(hipDataFlowServiceClient);
    }


    @Bean("hipDataflowRequestAction")
    public RetryableValidatedRequestAction<HipDataFlowServiceClient> hipDataflowRequestAction(
            DefaultValidatedRequestAction<HipDataFlowServiceClient> defalutHipDataflowRequestAction,
            AmqpTemplate amqpTemplate,
            Jackson2JsonMessageConverter converter,
            ServiceOptions serviceOptions) {
        return new RetryableValidatedRequestAction<>(amqpTemplate,
                converter,
                defalutHipDataflowRequestAction,
                serviceOptions,
                GW_DATAFLOW_QUEUE,
                X_HIP_ID);
    }

    @Bean("hipDataflowRequestOrchestrator")
    public RequestOrchestrator<HipDataFlowServiceClient> hipDataflowRequestOrchestrator(
            CacheAdapter<String, String> requestIdMappings,
            Validator validator,
            HipDataFlowServiceClient hipDataFlowServiceClient,
            RetryableValidatedRequestAction<HipDataFlowServiceClient> hipDataflowRequestAction) {
        return new RequestOrchestrator<>(requestIdMappings, validator, hipDataFlowServiceClient,hipDataflowRequestAction);
    }

    @Bean
    SimpleMessageListenerContainer container(
            ConnectionFactory connectionFactory,
            RetryableValidatedResponseAction<LinkConfirmServiceClient> retryableLinkResponseAction,
            RetryableValidatedRequestAction<HipDataFlowServiceClient> hipDataflowRequestAction) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setQueueNames(GW_LINK_QUEUE,GW_DATAFLOW_QUEUE);
        container.setMessageListener(retryableLinkResponseAction);
        container.setMessageListener(hipDataflowRequestAction);
        return container;
    }

    @Bean("hipDataFlowRequestResponseAction")
    public DefaultValidatedResponseAction<HipDataFlowServiceClient> hipDataFlowRequestResponseAction(
            HipDataFlowServiceClient hipDataFlowServiceClient) {
        return new DefaultValidatedResponseAction<>(hipDataFlowServiceClient);
    }
    @Bean("hipDataFlowRequestResponseOrchestrator")
    public ResponseOrchestrator hipDataFlowRequestResponseOrchestrator(
            Validator validator,
            DefaultValidatedResponseAction<HipDataFlowServiceClient> hipDataFlowRequestResponseAction) {
        return new ResponseOrchestrator(validator, hipDataFlowRequestResponseAction);
    }
}
