package bio.terra.landingzone.stairway.flight.create.resource.step;

import bio.terra.landingzone.library.landingzones.definition.ArmManagers;
import bio.terra.landingzone.library.landingzones.definition.ResourceNameGenerator;
import bio.terra.landingzone.library.landingzones.definition.factories.CromwellBaseResourcesFactory;
import bio.terra.landingzone.library.landingzones.definition.factories.ParametersResolver;
import bio.terra.landingzone.library.landingzones.deployment.LandingZoneTagKeys;
import bio.terra.landingzone.library.landingzones.deployment.SubnetResourcePurpose;
import bio.terra.landingzone.service.landingzone.azure.model.LandingZoneResource;
import bio.terra.landingzone.stairway.flight.LandingZoneFlightMapKeys;
import bio.terra.landingzone.stairway.flight.ResourceNameProvider;
import bio.terra.landingzone.stairway.flight.ResourceNameRequirements;
import bio.terra.stairway.FlightContext;
import com.azure.core.management.exception.ManagementException;
import com.azure.resourcemanager.network.models.Delegation;
import com.azure.resourcemanager.network.models.Network;
import com.azure.resourcemanager.network.models.ServiceEndpointPropertiesFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

public class CreateVnetStep extends BaseResourceCreateStep {
  private static final Logger logger = LoggerFactory.getLogger(CreateVnetStep.class);
  public static final String VNET_ID = "VNET_ID";
  public static final String VNET_RESOURCE_KEY = "VNET";

  public CreateVnetStep(
      ArmManagers armManagers,
      ParametersResolver parametersResolver,
      ResourceNameProvider resourceNameProvider) {
    super(armManagers, parametersResolver, resourceNameProvider);
  }

  @Override
  public void createResource(FlightContext context, ArmManagers armManagers) {
    String vNetName = resourceNameProvider.getName(getResourceType());
    Network vNet = createVnetAndSubnets(context, armManagers, vNetName);

    setupPostgresSubnet(context, armManagers, vNet);

    context.getWorkingMap().put(VNET_ID, vNet.id());
    context
        .getWorkingMap()
        .put(
            VNET_RESOURCE_KEY,
            LandingZoneResource.builder()
                .resourceId(vNet.id())
                .resourceType(vNet.type())
                .tags(vNet.tags())
                .region(vNet.regionName())
                .resourceName(vNet.name())
                .build());
    logger.info(RESOURCE_CREATED, getResourceType(), vNet.id(), getMRGName(context));
  }

  private Network createVnetAndSubnets(
      FlightContext context, ArmManagers armManagers, String vNetName) {
    var landingZoneId =
        getParameterOrThrow(
            context.getInputParameters(), LandingZoneFlightMapKeys.LANDING_ZONE_ID, UUID.class);

    try {
      return armManagers
          .azureResourceManager()
          .networks()
          .define(vNetName)
          .withRegion(getMRGRegionName(context))
          .withExistingResourceGroup(getMRGName(context))
          .withAddressSpace(
              parametersResolver.getValue(
                  CromwellBaseResourcesFactory.ParametersNames.VNET_ADDRESS_SPACE.name()))
          .withSubnet(
              CromwellBaseResourcesFactory.Subnet.AKS_SUBNET.name(),
              parametersResolver.getValue(CromwellBaseResourcesFactory.Subnet.AKS_SUBNET.name()))
          .withSubnet(
              CromwellBaseResourcesFactory.Subnet.BATCH_SUBNET.name(),
              parametersResolver.getValue(CromwellBaseResourcesFactory.Subnet.BATCH_SUBNET.name()))
          .withSubnet(
              CromwellBaseResourcesFactory.Subnet.POSTGRESQL_SUBNET.name(),
              parametersResolver.getValue(
                  CromwellBaseResourcesFactory.Subnet.POSTGRESQL_SUBNET.name()))
          .withSubnet(
              CromwellBaseResourcesFactory.Subnet.COMPUTE_SUBNET.name(),
              parametersResolver.getValue(
                  CromwellBaseResourcesFactory.Subnet.COMPUTE_SUBNET.name()))
          .withTags(
              Map.of(
                  LandingZoneTagKeys.LANDING_ZONE_ID.toString(),
                  landingZoneId.toString(),
                  SubnetResourcePurpose.AKS_NODE_POOL_SUBNET.toString(),
                  CromwellBaseResourcesFactory.Subnet.AKS_SUBNET.name(),
                  SubnetResourcePurpose.WORKSPACE_BATCH_SUBNET.toString(),
                  CromwellBaseResourcesFactory.Subnet.BATCH_SUBNET.name(),
                  SubnetResourcePurpose.POSTGRESQL_SUBNET.toString(),
                  CromwellBaseResourcesFactory.Subnet.POSTGRESQL_SUBNET.name(),
                  SubnetResourcePurpose.WORKSPACE_COMPUTE_SUBNET.toString(),
                  CromwellBaseResourcesFactory.Subnet.COMPUTE_SUBNET.name()))
          .create();
    } catch (ManagementException e) {
      // resource may already exist if this step is being retried
      if (e.getResponse() != null
          && HttpStatus.CONFLICT.value() == e.getResponse().getStatusCode()) {
        return armManagers
            .azureResourceManager()
            .networks()
            .getByResourceGroup(getMRGName(context), vNetName);
      } else {
        throw e;
      }
    }
  }

  /**
   * https://learn.microsoft.com/en-us/azure/postgresql/flexible-server/how-to-manage-virtual-network-portal
   */
  private void setupPostgresSubnet(FlightContext context, ArmManagers armManagers, Network vNet) {
    var postgresSubnet =
        vNet.subnets()
            .get(CromwellBaseResourcesFactory.Subnet.POSTGRESQL_SUBNET.name())
            .innerModel();

    armManagers
        .azureResourceManager()
        .networks()
        .manager()
        .serviceClient()
        .getSubnets()
        .createOrUpdate(
            getMRGName(context),
            vNet.name(),
            CromwellBaseResourcesFactory.Subnet.POSTGRESQL_SUBNET.name(),
            postgresSubnet
                .withDelegations(
                    List.of(
                        new Delegation()
                            .withName("dlg-Microsoft.DBforPostgreSQL-flexibleServers")
                            .withType("Microsoft.Network/virtualNetworks/subnets/delegations")
                            .withServiceName("Microsoft.DBforPostgreSQL/flexibleServers")))
                .withServiceEndpoints(
                    List.of(
                        new ServiceEndpointPropertiesFormat().withService("Microsoft.storage"))));
  }

  @Override
  protected void deleteResource(String resourceId) {
    armManagers.azureResourceManager().networks().deleteById(resourceId);
  }

  @Override
  public String getResourceType() {
    return "VirtualNetwork";
  }

  @Override
  protected Optional<String> getResourceId(FlightContext context) {
    return Optional.ofNullable(context.getWorkingMap().get(VNET_ID, String.class));
  }

  @Override
  public List<ResourceNameRequirements> getResourceNameRequirements() {
    return List.of(
        new ResourceNameRequirements(
            getResourceType(), ResourceNameGenerator.MAX_VNET_NAME_LENGTH));
  }
}
