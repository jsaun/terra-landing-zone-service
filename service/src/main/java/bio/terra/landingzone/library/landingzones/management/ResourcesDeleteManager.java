package bio.terra.landingzone.library.landingzones.management;

import bio.terra.landingzone.common.VirtualNetworkLinkResourceHelper;
import bio.terra.landingzone.library.landingzones.definition.ArmManagers;
import bio.terra.landingzone.library.landingzones.deployment.LandingZoneTagKeys;
import bio.terra.landingzone.library.landingzones.management.deleterules.LandingZoneRuleDeleteException;
import com.azure.core.util.logging.ClientLogger;
import com.azure.resourcemanager.network.models.PrivateEndpoint;
import com.azure.resourcemanager.resources.models.GenericResource;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ResourcesDeleteManager {
  private final ArmManagers armManagers;

  private final DeleteRulesVerifier deleteRulesVerifier;

  private static final ClientLogger logger = new ClientLogger(ResourcesDeleteManager.class);

  public ResourcesDeleteManager(ArmManagers armManagers, DeleteRulesVerifier deleteRulesVerifier) {
    this.armManagers = armManagers;
    this.deleteRulesVerifier = deleteRulesVerifier;
  }

  /***
   * Deletes Azure resources in the landing zone. Rules that assess the current state of the resources are executed before attempting a delete operation.
   * These rules determine if resources in their current state can be deleted.
   * @param landingZoneId the landing zone id.
   * @param resourceGroupName resource group where the landing zone resources are deployed.
   * @return A list of the resources that were deleted.
   * @throws LandingZoneRuleDeleteException If at least one resource can't be deleted in its current state.
   */
  public List<GenericResource> deleteLandingZoneResources(
      String landingZoneId, String resourceGroupName) throws LandingZoneRuleDeleteException {

    final List<ResourceToDelete> resourcesToDelete =
        listLandingZoneResourcesToDelete(landingZoneId, resourceGroupName);

    deleteRulesVerifier.checkIfRulesAllowDelete(resourcesToDelete);

    return deleteLandingZoneResourcesInOrder(resourcesToDelete);
  }

  private List<ResourceToDelete> listLandingZoneResourcesToDelete(
      String landingZoneId, String resourceGroupName) {
    final List<PrivateEndpoint> privateEndPoints =
        armManagers
            .azureResourceManager()
            .privateEndpoints()
            .listByResourceGroup(resourceGroupName)
            .stream()
            .toList();

    // Deploying AKS with monitoring connected to a log analytics workspace also deploys a
    // container insights solution named `ContainerInsights(WORKSPACE_ID)` which is untagged
    // this code lists them all and later code figures out which to delete
    var solutions =
        armManagers
            .azureResourceManager()
            .genericResources()
            .listByResourceGroup(resourceGroupName)
            .stream()
            .filter(
                r ->
                    AzureResourceTypeUtils.AZURE_SOLUTIONS_TYPE.equalsIgnoreCase(
                        "%s/%s".formatted(r.resourceProviderNamespace(), r.resourceType())))
            .toList();

    return armManagers
        .azureResourceManager()
        .genericResources()
        .listByResourceGroup(resourceGroupName)
        .stream()
        .filter(
            r ->
                r.tags()
                    .getOrDefault(LandingZoneTagKeys.LANDING_ZONE_ID.toString(), "")
                    .equals(landingZoneId))
        .map(r -> toResourceToDelete(r, privateEndPoints, solutions))
        .toList();
  }

  private ResourceToDelete toResourceToDelete(
      GenericResource genericResource,
      List<PrivateEndpoint> privateEndPoints,
      List<GenericResource> solutions) {

    PrivateEndpoint privateEndPoint = null;
    List<GenericResource> resourceRelatedSolutions = null;
    if (privateEndPoints != null) {
      privateEndPoint =
          privateEndPoints.stream()
              .filter(
                  p ->
                      p.privateLinkServiceConnections().values().stream()
                          .anyMatch(
                              c ->
                                  c.privateLinkResourceId().equalsIgnoreCase(genericResource.id())))
              .findFirst()
              .orElse(null);
    }

    if (solutions != null) {
      resourceRelatedSolutions =
          solutions.stream().filter(s -> s.name().contains(genericResource.name())).toList();
    }

    return new ResourceToDelete(genericResource, privateEndPoint, resourceRelatedSolutions);
  }

  private List<GenericResource> deleteLandingZoneResourcesInOrder(
      List<ResourceToDelete> resourcesToDelete) {

    // The first partition (false) contains all the resources that can be deleted first (independent
    // resources)
    // the second partition (true) contains resources that are considered foundational
    // for the landing zone (base resources) and therefore should be deleted last.
    // The enum LandingZoneDependentResourceType determines the types that are considered base
    // resources.

    Map<Boolean, List<ResourceToDelete>> partitions =
        resourcesToDelete.stream().collect(Collectors.partitioningBy(this::isBaseResource));

    return Stream.concat(
            partitions.get(false).stream().map(this::deleteResource),
            partitions.get(true).stream().map(this::deleteResource))
        .toList();
  }

  private GenericResource deleteResource(ResourceToDelete resourceToDelete) {
    logger.info("Deleting landing zone resource: {}", resourceToDelete.resource().id());

    if (resourceToDelete.privateEndpoint() != null) {
      logger.info(
          "Deleting landing zone private endpoint {} for resource: {}",
          resourceToDelete.privateEndpoint().id(),
          resourceToDelete.resource().id());

      armManagers
          .azureResourceManager()
          .genericResources()
          .deleteById(resourceToDelete.privateEndpoint().id());

      logger.info("Resource deleted. id:{}", resourceToDelete.privateEndpoint().id());
    }

    if (resourceToDelete.solutions() != null && !resourceToDelete.solutions().isEmpty()) {
      resourceToDelete
          .solutions()
          .forEach(
              solution -> {
                logger.info(
                    "Deleting landing zone solution {} for resource: {}",
                    solution.id(),
                    resourceToDelete.resource().id());

                armManagers.azureResourceManager().genericResources().deleteById(solution.id());

                logger.info("Resource deleted. id:{}", solution.id());
              });
    }

    if (resourceToDelete.resource().id().contains("virtualNetworkLinks")) {
      // this is temporary solution since generic deletion is failing for this type of resource
      VirtualNetworkLinkResourceHelper.delete(armManagers, resourceToDelete.resource().id());
    } else {
      armManagers
          .azureResourceManager()
          .genericResources()
          .deleteById(resourceToDelete.resource().id());
    }
    logger.info("Resource deleted. id:{}", resourceToDelete.resource().id());

    return resourceToDelete.resource();
  }

  private boolean isBaseResource(ResourceToDelete resourceToDelete) {
    return LandingZoneBaseResourceType.containsValueIgnoringCase(
        resourceToDelete.resource().type());
  }
}
