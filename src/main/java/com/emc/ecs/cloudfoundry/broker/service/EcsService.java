package com.emc.ecs.cloudfoundry.broker.service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.servicebroker.exception.ServiceInstanceExistsException;
import org.springframework.stereotype.Service;

import com.emc.ecs.cloudfoundry.broker.EcsManagementClientException;
import com.emc.ecs.cloudfoundry.broker.EcsManagementResourceNotFoundException;
import com.emc.ecs.cloudfoundry.broker.config.BrokerConfig;
import com.emc.ecs.cloudfoundry.broker.config.CatalogConfig;
import com.emc.ecs.cloudfoundry.broker.model.NamespaceQuotaParam;
import com.emc.ecs.cloudfoundry.broker.model.PlanProxy;
import com.emc.ecs.cloudfoundry.broker.model.ServiceDefinitionProxy;
import com.emc.ecs.management.sdk.BaseUrlAction;
import com.emc.ecs.management.sdk.BucketAclAction;
import com.emc.ecs.management.sdk.BucketAction;
import com.emc.ecs.management.sdk.BucketQuotaAction;
import com.emc.ecs.management.sdk.Connection;
import com.emc.ecs.management.sdk.NamespaceAction;
import com.emc.ecs.management.sdk.NamespaceQuotaAction;
import com.emc.ecs.management.sdk.NamespaceRetentionAction;
import com.emc.ecs.management.sdk.ObjectUserAction;
import com.emc.ecs.management.sdk.ObjectUserSecretAction;
import com.emc.ecs.management.sdk.ReplicationGroupAction;
import com.emc.ecs.management.sdk.model.BaseUrl;
import com.emc.ecs.management.sdk.model.BucketAcl;
import com.emc.ecs.management.sdk.model.BucketUserAcl;
import com.emc.ecs.management.sdk.model.NamespaceCreate;
import com.emc.ecs.management.sdk.model.NamespaceUpdate;
import com.emc.ecs.management.sdk.model.ObjectBucketCreate;
import com.emc.ecs.management.sdk.model.RetentionClassCreate;
import com.emc.ecs.management.sdk.model.RetentionClassUpdate;
import com.emc.ecs.management.sdk.model.UserSecretKey;

@Service
public class EcsService {

    private static final String RETENTION = "retention";
    private static final String SERVICE_NOT_FOUND = "No service matching service id: ";

    @Autowired
    private Connection connection;

    @Autowired
    private BrokerConfig broker;

    @Autowired
    private CatalogConfig catalog;

    private String replicationGroupID;
    private String objectEndpoint;

    public String getObjectEndpoint() {
	return objectEndpoint;
    }

    @PostConstruct
    public void initialize() throws EcsManagementClientException,
	    EcsManagementResourceNotFoundException {
	lookupObjectEndpoints();
	lookupReplicationGroupID();
	prepareRepository();
    }

    public void deleteBucket(String id) throws EcsManagementClientException {
	BucketAction.delete(connection, prefix(id), broker.getNamespace());
    }

    public void createBucket(String id, ServiceDefinitionProxy service,
	    PlanProxy plan) throws EcsManagementClientException,
	    EcsManagementResourceNotFoundException {
	createBucket(id, service, plan, false);
    }

    public void createBucket(String id, ServiceDefinitionProxy service,
	    PlanProxy plan, Boolean errorOnExists)
	    throws EcsManagementClientException,
	    EcsManagementResourceNotFoundException {

	if (bucketExists(id)) {
	    if (errorOnExists)
		throw new ServiceInstanceExistsException(id, service.getId());
	    return;
	}

	ObjectBucketCreate createParam = new ObjectBucketCreate();
	createParam.setName(prefix(id));
	createParam.setNamespace(broker.getNamespace());
	createParam.setVpool(replicationGroupID);
	createParam.setHeadType(service.getHeadType());
	createParam.setFilesystemEnabled(service.getFileSystemEnabled());
	createParam.setIsStaleAllowed(service.getStaleAllowed());

	BucketAction.create(connection, createParam);

	int limit = plan.getQuotaLimit();
	int warning = plan.getQuotaWarning();

	// no quota needed if neither is set
	if (limit != -1 || warning != -1)
	    BucketQuotaAction.create(connection, prefix(id),
		    broker.getNamespace(), limit, warning);
    }

    public void changeBucketPlan(String id, ServiceDefinitionProxy service,
	    PlanProxy plan) throws EcsManagementClientException {
	int limit = plan.getQuotaLimit();
	int warning = plan.getQuotaWarning();
	if (limit == -1 && warning == -1) {
	    BucketQuotaAction.delete(connection, prefix(id),
		    broker.getNamespace());
	} else {
	    BucketQuotaAction.create(connection, prefix(id),
		    broker.getNamespace(), limit, warning);
	}
    }

    public boolean bucketExists(String id) throws EcsManagementClientException {
	return BucketAction.exists(connection, prefix(id),
		broker.getNamespace());
    }

    public UserSecretKey createUser(String id)
	    throws EcsManagementClientException {
	ObjectUserAction.create(connection, prefix(id), broker.getNamespace());
	ObjectUserSecretAction.create(connection, prefix(id));
	return ObjectUserSecretAction.list(connection, prefix(id)).get(0);
    }

    public UserSecretKey createUser(String id, String namespace)
	    throws EcsManagementClientException {
	ObjectUserAction.create(connection, prefix(id), prefix(namespace));
	ObjectUserSecretAction.create(connection, prefix(id));
	return ObjectUserSecretAction.list(connection, prefix(id)).get(0);
    }

    public Boolean userExists(String id) throws EcsManagementClientException {
	return ObjectUserAction.exists(connection, prefix(id),
		broker.getNamespace());
    }

    public void deleteUser(String id) throws EcsManagementClientException {
	ObjectUserAction.delete(connection, prefix(id));
    }

    public void addUserToBucket(String id, String username)
	    throws EcsManagementClientException {
	addUserToBucket(id, username, Arrays.asList("full_control"));
    }

    public void addUserToBucket(String id, String username,
	    List<String> permissions) throws EcsManagementClientException {
	BucketAcl acl = BucketAclAction.get(connection, prefix(id),
		broker.getNamespace());
	List<BucketUserAcl> userAcl = acl.getAcl().getUserAccessList();
	userAcl.add(new BucketUserAcl(prefix(username), permissions));
	acl.getAcl().setUserAccessList(userAcl);
	BucketAclAction.update(connection, prefix(id), acl);
    }

    public void removeUserFromBucket(String id, String username)
	    throws EcsManagementClientException {
	BucketAcl acl = BucketAclAction.get(connection, prefix(id),
		broker.getNamespace());
	List<BucketUserAcl> newUserAcl = acl.getAcl().getUserAccessList()
		.stream().filter(a -> !a.getUser().equals(prefix(username)))
		.collect(Collectors.toList());
	acl.getAcl().setUserAccessList(newUserAcl);
	BucketAclAction.update(connection, prefix(id), acl);
    }

    public String prefix(String string) {
	return broker.getPrefix() + string;
    }

    private void lookupObjectEndpoints() throws EcsManagementClientException,
	    EcsManagementResourceNotFoundException {
	if (broker.getObjectEndpoint() != null) {
	    objectEndpoint = broker.getObjectEndpoint();
	} else {
	    List<BaseUrl> baseUrlList = BaseUrlAction.list(connection);
	    String urlId;

	    if (baseUrlList.isEmpty()) {
		throw new EcsManagementClientException(
			"No object endpoint or base URL available");
	    } else if (broker.getBaseUrl() != null) {
		urlId = baseUrlList.stream()
			.filter(b -> broker.getBaseUrl().equals(b.getName()))
			.findFirst().get().getId();
	    } else {
		urlId = detectDefaultBaseUrlId(baseUrlList);
	    }

	    // TODO: switch to TLS end-point and custom S3 trust manager
	    objectEndpoint = BaseUrlAction.get(connection, urlId)
		    .getNamespaceUrl(broker.getNamespace(), false);
	}
	if (broker.getRepositoryEndpoint() == null)
	    broker.setRepositoryEndpoint(objectEndpoint);
    }

    private void lookupReplicationGroupID()
	    throws EcsManagementClientException {
	replicationGroupID = ReplicationGroupAction.list(connection).stream()
		.filter(r -> broker.getReplicationGroup().equals(r.getName()))
		.findFirst().get().getId();
    }

    private void prepareRepository() throws EcsManagementClientException,
	    EcsManagementResourceNotFoundException {
	String bucketName = broker.getRepositoryBucket();
	String userName = broker.getRepositoryUser();
	if (!bucketExists(bucketName)) {
	    ServiceDefinitionProxy service = lookupServiceDefinition(
		    broker.getRepositoryServiceId());
	    createBucket(bucketName, service,
		    service.findPlan(broker.getRepositoryPlanId()));
	}

	if (!userExists(userName)) {
	    UserSecretKey secretKey = createUser(userName);
	    addUserToBucket(bucketName, userName);
	    broker.setRepositorySecret(secretKey.getSecretKey());
	} else {
	    broker.setRepositorySecret(getUserSecret(userName));
	}
    }

    private String getUserSecret(String id)
	    throws EcsManagementClientException {
	return ObjectUserSecretAction.list(connection, prefix(id)).get(0)
		.getSecretKey();
    }

    private String detectDefaultBaseUrlId(List<BaseUrl> baseUrlList) {
	Optional<BaseUrl> maybeBaseUrl = baseUrlList.stream()
		.filter(b -> "DefaultBaseUrl".equals(b.getName())).findAny();
	if (maybeBaseUrl.isPresent())
	    return maybeBaseUrl.get().getId();
	return baseUrlList.get(0).getId();
    }

    public Boolean namespaceExists(String id)
	    throws EcsManagementClientException {
	return NamespaceAction.exists(connection, prefix(id));
    }

    public void createNamespace(String id, ServiceDefinitionProxy service,
	    PlanProxy plan, Optional<Map<String,Object>> maybeParameters)
	    throws EcsManagementClientException {
	if (namespaceExists(id))
	    throw new ServiceInstanceExistsException(id, service.getId());

	Map<String, Object> parameters = maybeParameters.orElse(new HashMap<>());

	parameters.putAll(plan.getServiceSettings());
	parameters.putAll(service.getServiceSettings());
	NamespaceAction.create(connection, new NamespaceCreate(prefix(id),
		replicationGroupID, parameters));

	if (parameters.containsKey("quota")) {
	    @SuppressWarnings("unchecked")
	    Map<String, Integer> quota = (Map<String, Integer>) parameters
		    .get("quota");
	    NamespaceQuotaParam quotaParam = new NamespaceQuotaParam(id,
		    quota.get("limit"), quota.get("warn"));
	    NamespaceQuotaAction.create(connection, prefix(id), quotaParam);
	}

	if (parameters.containsKey(RETENTION)) {
	    @SuppressWarnings("unchecked")
	    Map<String, Integer> retention = (Map<String, Integer>) parameters
		    .get(RETENTION);
	    for (Map.Entry<String, Integer> entry : retention.entrySet()) {
		NamespaceRetentionAction.create(connection, prefix(id),
			new RetentionClassCreate(entry.getKey(),
				entry.getValue()));
	    }
	}
    }

    public void deleteNamespace(String id) throws EcsManagementClientException {
	NamespaceAction.delete(connection, prefix(id));
    }

    public void changeNamespacePlan(String id, ServiceDefinitionProxy service, PlanProxy plan,
	    Map<String, Object> parameters)
	    throws EcsManagementClientException {
	parameters.putAll(plan.getServiceSettings());
	parameters.putAll(service.getServiceSettings());
	NamespaceAction.update(connection, prefix(id),
		new NamespaceUpdate(parameters));

	if (parameters.containsKey(RETENTION)) {
	    @SuppressWarnings("unchecked")
	    Map<String, Integer> retention = (Map<String, Integer>) parameters
		    .get(RETENTION);
	    for (Map.Entry<String, Integer> entry : retention.entrySet()) {
		if (NamespaceRetentionAction.exists(connection, id,
			entry.getKey())) {
		    if (-1 == entry.getValue()) {
			NamespaceRetentionAction.delete(connection, prefix(id),
				entry.getKey());
		    } else {
			NamespaceRetentionAction.update(connection, prefix(id),
				entry.getKey(),
				new RetentionClassUpdate(entry.getValue()));
		    }
		} else {
		    NamespaceRetentionAction.create(connection, prefix(id),
			    new RetentionClassCreate(entry.getKey(),
				    entry.getValue()));
		}
	    }
	}
    }

    public ServiceDefinitionProxy lookupServiceDefinition(
	    String serviceDefinitionId) throws EcsManagementClientException {
	ServiceDefinitionProxy service = catalog
		.findServiceDefinition(serviceDefinitionId);
	if (service == null)
	    throw new EcsManagementClientException(
		    SERVICE_NOT_FOUND + serviceDefinitionId);
	return service;
    }
}