package bio.terra.landingzone.stairway.flight.create.resource.step;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import bio.terra.landingzone.library.landingzones.definition.factories.CromwellBaseResourcesFactory;
import bio.terra.landingzone.stairway.flight.FlightTestUtils;
import bio.terra.landingzone.stairway.flight.LandingZoneFlightMapKeys;
import bio.terra.landingzone.stairway.flight.exception.MissingRequiredFieldsException;
import bio.terra.profile.model.ProfileModel;
import bio.terra.stairway.FlightMap;
import bio.terra.stairway.StepResult;
import com.azure.resourcemanager.network.NetworkManager;
import com.azure.resourcemanager.network.fluent.NetworkManagementClient;
import com.azure.resourcemanager.network.fluent.SubnetsClient;
import com.azure.resourcemanager.network.fluent.models.SubnetInner;
import com.azure.resourcemanager.network.models.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class CreateVnetStepTest extends BaseStepTest {
  @Mock private Networks mockNetworks;
  @Mock private Network.DefinitionStages.Blank mockDefinitionStageBlack;
  @Mock private Network.DefinitionStages.WithGroup mockDefinitionStageWithGroup;
  @Mock private Network.DefinitionStages.WithCreate mockDefinitionStageWithCreate;
  @Mock private Network.DefinitionStages.WithCreateAndSubnet mockDefinitionStageWithCreateAndSubnet;

  @Captor private ArgumentCaptor<Map<String, String>> vnetTagsCaptor;
  @Captor private ArgumentCaptor<List<Delegation>> delegationsArgumentCaptor;

  @Captor
  private ArgumentCaptor<List<ServiceEndpointPropertiesFormat>> serviceEndpointsArgumentCaptor;

  private CreateVnetStep createVnetStep;
  @Mock private NetworkManager mockNetworkManager;
  @Mock private NetworkManagementClient mockServiceClient;
  @Mock private SubnetsClient mockSubnets;

  @BeforeEach
  void setup() {
    createVnetStep =
        new CreateVnetStep(mockArmManagers, mockParametersResolver, mockResourceNameProvider);
  }

  @Test
  void doStepSuccess() throws InterruptedException {
    when(mockResourceNameProvider.getName(createVnetStep.getResourceType())).thenReturn(VNET_NAME);

    setupFlightContext(
        mockFlightContext,
        Map.of(
            LandingZoneFlightMapKeys.BILLING_PROFILE,
            new ProfileModel().id(UUID.randomUUID()),
            LandingZoneFlightMapKeys.LANDING_ZONE_ID,
            LANDING_ZONE_ID),
        Map.of(GetManagedResourceGroupInfo.TARGET_MRG_KEY, ResourceStepFixture.createDefaultMrg()));
    setupParameterResolver();

    var network = mock(Network.class);
    var postgresSubnet = mock(Subnet.class);
    var postgresSubnetInner = mock(SubnetInner.class);
    when(network.id()).thenReturn(VNET_ID);
    when(network.subnets())
        .thenReturn(
            Map.of(CromwellBaseResourcesFactory.Subnet.POSTGRESQL_SUBNET.name(), postgresSubnet));
    when(postgresSubnet.innerModel()).thenReturn(postgresSubnetInner);
    when(postgresSubnetInner.withDelegations(delegationsArgumentCaptor.capture()))
        .thenReturn(postgresSubnetInner);
    when(postgresSubnetInner.withServiceEndpoints(serviceEndpointsArgumentCaptor.capture()))
        .thenReturn(postgresSubnetInner);
    setupArmManagersForDoStep(network);

    var stepResult = createVnetStep.doStep(mockFlightContext);

    assertTrue(mockFlightContext.getWorkingMap().containsKey(CreateVnetStep.VNET_ID));
    assertThat(
        mockFlightContext.getWorkingMap().get(CreateVnetStep.VNET_ID, String.class),
        equalTo(VNET_ID));

    assertThat(
        delegationsArgumentCaptor.getValue().get(0).serviceName(),
        equalTo("Microsoft.DBforPostgreSQL/flexibleServers"));
    assertThat(
        serviceEndpointsArgumentCaptor.getValue().get(0).service(), equalTo("Microsoft.storage"));
    // verifyBasicTags(vnetTagsCaptor, LANDING_ZONE_ID);
    assertThat(stepResult, equalTo(StepResult.getStepResultSuccess()));
    verify(mockDefinitionStageWithCreate, times(1)).create();
    verifyNoMoreInteractions(mockDefinitionStageWithCreate);
  }

  @ParameterizedTest
  @MethodSource("inputParameterProvider")
  void doStepMissingInputParameterThrowsException(Map<String, Object> inputParameters) {
    FlightMap flightMapInputParameters =
        FlightTestUtils.prepareFlightInputParameters(inputParameters);
    when(mockFlightContext.getInputParameters()).thenReturn(flightMapInputParameters);

    assertThrows(
        MissingRequiredFieldsException.class, () -> createVnetStep.doStep(mockFlightContext));
  }

  @Test
  void undoStepSuccess() throws InterruptedException {
    var workingMap = new FlightMap();
    workingMap.put(CreateVnetStep.VNET_ID, VNET_ID);
    when(mockFlightContext.getWorkingMap()).thenReturn(workingMap);

    when(mockAzureResourceManager.networks()).thenReturn(mockNetworks);
    when(mockArmManagers.azureResourceManager()).thenReturn(mockAzureResourceManager);

    var stepResult = createVnetStep.undoStep(mockFlightContext);

    verify(mockNetworks, times(1)).deleteById(VNET_ID);
    assertThat(stepResult, equalTo(StepResult.getStepResultSuccess()));
  }

  @Test
  void undoStepSuccessWhenDoStepFailed() throws InterruptedException {
    var workingMap = new FlightMap(); // empty, there is no VNET_ID key
    when(mockFlightContext.getWorkingMap()).thenReturn(workingMap);

    var stepResult = createVnetStep.undoStep(mockFlightContext);

    verify(mockNetworks, never()).deleteById(anyString());
    assertThat(stepResult, equalTo(StepResult.getStepResultSuccess()));
  }

  private void setupArmManagersForDoStep(Network result) {
    when(mockDefinitionStageWithCreateAndSubnet.withTags(vnetTagsCaptor.capture()))
        .thenReturn(mockDefinitionStageWithCreate);
    when(mockDefinitionStageWithCreateAndSubnet.withSubnet(anyString(), anyString()))
        .thenReturn(mockDefinitionStageWithCreateAndSubnet);
    when(mockDefinitionStageWithCreate.withAddressSpace(anyString()))
        .thenReturn(mockDefinitionStageWithCreateAndSubnet);
    when(mockDefinitionStageWithGroup.withExistingResourceGroup(anyString()))
        .thenReturn(mockDefinitionStageWithCreate);
    when(mockDefinitionStageBlack.withRegion(anyString())).thenReturn(mockDefinitionStageWithGroup);
    when(mockNetworks.define(anyString())).thenReturn(mockDefinitionStageBlack);
    when(mockAzureResourceManager.networks()).thenReturn(mockNetworks);
    when(mockArmManagers.azureResourceManager()).thenReturn(mockAzureResourceManager);
    when(mockDefinitionStageWithCreate.create()).thenReturn(result);

    when(mockNetworks.manager()).thenReturn(mockNetworkManager);
    when(mockNetworkManager.serviceClient()).thenReturn(mockServiceClient);
    when(mockServiceClient.getSubnets()).thenReturn(mockSubnets);
  }

  private void setupParameterResolver() {
    when(mockParametersResolver.getValue(
            CromwellBaseResourcesFactory.ParametersNames.VNET_ADDRESS_SPACE.name()))
        .thenReturn("10.1.0.0/27");
    when(mockParametersResolver.getValue(CromwellBaseResourcesFactory.Subnet.AKS_SUBNET.name()))
        .thenReturn("10.1.0.0/29");
    when(mockParametersResolver.getValue(CromwellBaseResourcesFactory.Subnet.BATCH_SUBNET.name()))
        .thenReturn("10.1.0.8/29");
    when(mockParametersResolver.getValue(
            CromwellBaseResourcesFactory.Subnet.POSTGRESQL_SUBNET.name()))
        .thenReturn("10.1.0.16/29");
    when(mockParametersResolver.getValue(CromwellBaseResourcesFactory.Subnet.COMPUTE_SUBNET.name()))
        .thenReturn("10.1.0.24/29");
  }
}
