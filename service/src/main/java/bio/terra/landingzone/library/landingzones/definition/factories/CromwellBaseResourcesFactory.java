package bio.terra.landingzone.library.landingzones.definition.factories;

import bio.terra.landingzone.library.landingzones.definition.ArmManagers;
import bio.terra.landingzone.library.landingzones.definition.DefinitionContext;
import bio.terra.landingzone.library.landingzones.definition.DefinitionHeader;
import bio.terra.landingzone.library.landingzones.definition.DefinitionVersion;
import bio.terra.landingzone.library.landingzones.definition.LandingZoneDefinable;
import bio.terra.landingzone.library.landingzones.definition.LandingZoneDefinition;
import bio.terra.landingzone.library.landingzones.definition.ResourceNameGenerator;
import bio.terra.landingzone.library.landingzones.deployment.LandingZoneDeployment.DefinitionStages.Deployable;
import bio.terra.landingzone.library.landingzones.deployment.LandingZoneDeployment.DefinitionStages.WithLandingZoneResource;
import bio.terra.landingzone.library.landingzones.deployment.ResourcePurpose;
import bio.terra.landingzone.library.landingzones.deployment.SubnetResourcePurpose;
import com.azure.core.util.logging.ClientLogger;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.containerservice.models.AgentPoolMode;
import com.azure.resourcemanager.containerservice.models.ContainerServiceVMSizeTypes;
import com.azure.resourcemanager.network.models.Network;
import com.azure.resourcemanager.network.models.PrivateLinkSubResourceName;
import com.azure.resourcemanager.postgresql.models.PublicNetworkAccessEnum;
import com.azure.resourcemanager.postgresql.models.ServerPropertiesForDefaultCreate;
import com.azure.resourcemanager.postgresql.models.ServerVersion;
import com.azure.resourcemanager.postgresql.models.Sku;
import com.azure.resourcemanager.resources.models.ResourceGroup;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * An implementation of {@link LandingZoneDefinitionFactory} that deploys resources required for
 * cromwell. Current resources are: - VNet: Subnets required for AKS, Batch, PostgreSQL and
 * Compute/VMs - AKS Account (?) TODO - AKS Nodepool TODO - Batch Account TODO - Storage Account
 * TODO - PostgreSQL server TODO
 */
public class CromwellBaseResourcesFactory extends ArmClientsDefinitionFactory {
  private final String LZ_NAME = "Cromwell Landing Zone Base Resources";
  private final String LZ_DESC =
      "Cromwell Base Resources: VNet, AKS Account & Nodepool, Batch Account,"
          + " Storage Account, PostgreSQL server, Subnets for AKS, Batch, Posgres, and Compute";

  enum Subnet {
    AKS_SUBNET,
    BATCH_SUBNET,
    POSTGRESQL_SUBNET,
    COMPUTE_SUBNET
  }

  enum ParametersNames {
    POSTGRES_DB_ADMIN,
    POSTGRES_DB_PASSWORD,
    POSTGRES_SERVER_SKU,
    VNET_ADDRESS_SPACE
  }

  CromwellBaseResourcesFactory() {}

  public CromwellBaseResourcesFactory(ArmManagers armManagers) {
    super(armManagers);
  }

  @Override
  public DefinitionHeader header() {
    return new DefinitionHeader(LZ_NAME, LZ_DESC);
  }

  @Override
  public List<DefinitionVersion> availableVersions() {
    return List.of(DefinitionVersion.V1);
  }

  @Override
  public LandingZoneDefinable create(DefinitionVersion version) {
    if (version.equals(DefinitionVersion.V1)) {
      return new DefinitionV1(armManagers);
    }
    throw new RuntimeException("Invalid Version");
  }

  class DefinitionV1 extends LandingZoneDefinition {

    private final ClientLogger logger = new ClientLogger(DefinitionV1.class);

    protected DefinitionV1(ArmManagers armManagers) {
      super(armManagers);
    }

    @Override
    public Deployable definition(DefinitionContext definitionContext) {
      AzureResourceManager azureResourceManager = armManagers.azureResourceManager();
      WithLandingZoneResource deployment = definitionContext.deployment();
      ResourceGroup resourceGroup = definitionContext.resourceGroup();
      ResourceNameGenerator nameGenerator = definitionContext.resourceNameGenerator();
      ParametersResolver parametersResolver =
          new ParametersResolver(definitionContext.parameters(), getDefaultParameters());

      var vNet =
          azureResourceManager
              .networks()
              .define(nameGenerator.nextName(ResourceNameGenerator.MAX_VNET_NAME_LENGTH))
              .withRegion(resourceGroup.region())
              .withExistingResourceGroup(resourceGroup)
              .withAddressSpace(
                  parametersResolver.getValue(ParametersNames.VNET_ADDRESS_SPACE.name()))
              .withSubnet(
                  Subnet.AKS_SUBNET.name(), parametersResolver.getValue(Subnet.AKS_SUBNET.name()))
              .withSubnet(
                  Subnet.BATCH_SUBNET.name(),
                  parametersResolver.getValue(Subnet.BATCH_SUBNET.name()))
              .withSubnet(
                  Subnet.POSTGRESQL_SUBNET.name(),
                  parametersResolver.getValue(Subnet.POSTGRESQL_SUBNET.name()))
              .withSubnet(
                  Subnet.COMPUTE_SUBNET.name(),
                  parametersResolver.getValue(Subnet.COMPUTE_SUBNET.name()));

      var postgres =
          armManagers
              .postgreSqlManager()
              .servers()
              .define(
                  nameGenerator.nextName(ResourceNameGenerator.MAX_POSTGRESQL_SERVER_NAME_LENGTH))
              .withRegion(resourceGroup.region())
              .withExistingResourceGroup(resourceGroup.name())
              .withProperties(
                  new ServerPropertiesForDefaultCreate()
                      .withAdministratorLogin(
                          parametersResolver.getValue(ParametersNames.POSTGRES_DB_ADMIN.name()))
                      .withAdministratorLoginPassword(
                          parametersResolver.getValue(ParametersNames.POSTGRES_DB_PASSWORD.name()))
                      .withVersion(ServerVersion.ONE_ONE)
                      .withPublicNetworkAccess(PublicNetworkAccessEnum.DISABLED))
              .withSku(
                  new Sku()
                      .withName(
                          parametersResolver.getValue(ParametersNames.POSTGRES_SERVER_SKU.name())));

      var prerequisites =
          deployment
              .definePrerequisites()
              .withVNetWithPurpose(
                  vNet, Subnet.AKS_SUBNET.name(), SubnetResourcePurpose.AKS_NODE_POOL_SUBNET)
              .withVNetWithPurpose(
                  vNet, Subnet.BATCH_SUBNET.name(), SubnetResourcePurpose.WORKSPACE_BATCH_SUBNET)
              .withVNetWithPurpose(
                  vNet, Subnet.POSTGRESQL_SUBNET.name(), SubnetResourcePurpose.POSTGRESQL_SUBNET)
              .withVNetWithPurpose(
                  vNet,
                  Subnet.COMPUTE_SUBNET.name(),
                  SubnetResourcePurpose.WORKSPACE_COMPUTE_SUBNET)
              .withResourceWithPurpose(postgres, ResourcePurpose.SHARED_RESOURCE)
              .deploy();

      String vNetId =
          prerequisites.stream()
              .filter(
                  deployedResource ->
                      Objects.equals(
                          deployedResource.resourceType(), "Microsoft.Network/virtualNetworks"))
              .findFirst()
              .get()
              .resourceId();
      String postgreSqlId =
          prerequisites.stream()
              .filter(
                  deployedResource ->
                      Objects.equals(
                          deployedResource.resourceType(), "Microsoft.DBforPostgreSQL/servers"))
              .findFirst()
              .get()
              .resourceId();
      Network vNetwork = azureResourceManager.networks().getById(vNetId);

      var privateEndpoint =
          azureResourceManager
              .privateEndpoints()
              .define(
                  nameGenerator.nextName(ResourceNameGenerator.MAX_PRIVATE_ENDPOINT_NAME_LENGTH))
              .withRegion(resourceGroup.region())
              .withExistingResourceGroup(resourceGroup)
              .withSubnetId(vNetwork.subnets().get(Subnet.POSTGRESQL_SUBNET.name()).id())
              .definePrivateLinkServiceConnection(
                  nameGenerator.nextName(
                      ResourceNameGenerator.MAX_PRIVATE_LINK_CONNECTION_NAME_LENGTH))
              .withResourceId(postgreSqlId)
              .withSubResource(PrivateLinkSubResourceName.fromString("postgresqlServer"))
              .attach();

      var aks =
          azureResourceManager
              .kubernetesClusters()
              .define(nameGenerator.nextName(ResourceNameGenerator.MAX_AKS_CLUSTER_NAME_LENGTH))
              .withRegion(resourceGroup.region())
              .withExistingResourceGroup(resourceGroup)
              .withDefaultVersion()
              .withSystemAssignedManagedServiceIdentity()
              .defineAgentPool(
                  nameGenerator.nextName(ResourceNameGenerator.MAX_AKS_AGENT_POOL_NAME_LENGTH))
              .withVirtualMachineSize(ContainerServiceVMSizeTypes.STANDARD_A2_V2)
              .withAgentPoolVirtualMachineCount(1)
              .withAgentPoolMode(
                  AgentPoolMode.SYSTEM) // TODO VM Size? Pool Machine count? AgentPoolMode?
              .withVirtualNetwork(vNetwork.id(), Subnet.AKS_SUBNET.name())
              .attach()
              .withDnsPrefix(
                  nameGenerator.nextName(ResourceNameGenerator.MAX_AKS_DNS_PREFIX_NAME_LENGTH));

      var batch =
          armManagers
              .batchManager()
              .batchAccounts()
              .define(nameGenerator.nextName(ResourceNameGenerator.MAX_BATCH_ACCOUNT_NAME_LENGTH))
              .withRegion(resourceGroup.region())
              .withExistingResourceGroup(resourceGroup.name());

      var storage =
          azureResourceManager
              .storageAccounts()
              .define(nameGenerator.nextName(ResourceNameGenerator.MAX_STORAGE_ACCOUNT_NAME_LENGTH))
              .withRegion(resourceGroup.region())
              .withExistingResourceGroup(resourceGroup);

      var relay =
          armManagers
              .relayManager()
              .namespaces()
              .define(nameGenerator.nextName(ResourceNameGenerator.MAX_RELAY_NS_NAME_LENGTH))
              .withRegion(resourceGroup.region())
              .withExistingResourceGroup(resourceGroup.name());

      return deployment
          .withResourceWithPurpose(aks, ResourcePurpose.SHARED_RESOURCE)
          .withResourceWithPurpose(batch, ResourcePurpose.SHARED_RESOURCE)
          .withResourceWithPurpose(storage, ResourcePurpose.SHARED_RESOURCE)
          .withResourceWithPurpose(relay, ResourcePurpose.SHARED_RESOURCE)
          .withResourceWithPurpose(privateEndpoint, ResourcePurpose.SHARED_RESOURCE);
    }

    private Map<String, String> getDefaultParameters() {
      Map<String, String> defaultValues = new HashMap<>();
      defaultValues.put(ParametersNames.POSTGRES_DB_ADMIN.name(), "db_admin");
      defaultValues.put(ParametersNames.POSTGRES_DB_PASSWORD.name(), UUID.randomUUID().toString());
      defaultValues.put(ParametersNames.POSTGRES_SERVER_SKU.name(), "GP_Gen5_2");
      defaultValues.put(ParametersNames.VNET_ADDRESS_SPACE.name(), "10.1.0.0/27");
      defaultValues.put(Subnet.AKS_SUBNET.name(), "10.1.0.0/29");
      defaultValues.put(Subnet.BATCH_SUBNET.name(), "10.1.0.8/29");
      defaultValues.put(Subnet.POSTGRESQL_SUBNET.name(), "10.1.0.16/29");
      defaultValues.put(Subnet.COMPUTE_SUBNET.name(), "10.1.0.24/29");
      return defaultValues;
    }
  }
}