package bio.terra.landingzone.stairway.flight.create.resource.step;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import bio.terra.landingzone.stairway.common.model.TargetManagedResourceGroup;
import bio.terra.landingzone.stairway.flight.LandingZoneFlightMapKeys;
import bio.terra.landingzone.stairway.flight.exception.MissingRequiredFieldsException;
import bio.terra.landingzone.stairway.flight.utils.ProtectedDataAzureStorageHelper;
import bio.terra.profile.model.ProfileModel;
import com.azure.resourcemanager.loganalytics.models.DataExport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class ConnectLongTermLogStorageStepTest extends BaseStepTest {

  @Mock ProtectedDataAzureStorageHelper mockStorageHelper;

  @Test
  void doStepSuccess() throws InterruptedException {
    setupFlightContext(
        mockFlightContext,
        Map.of(
            LandingZoneFlightMapKeys.BILLING_PROFILE,
            new ProfileModel().id(UUID.randomUUID()),
            LandingZoneFlightMapKeys.LANDING_ZONE_ID,
            LANDING_ZONE_ID),
        Map.of(
            GetManagedResourceGroupInfo.TARGET_MRG_KEY,
            new TargetManagedResourceGroup("fake_mrg", "fake_mrg_region"),
            CreateLogAnalyticsWorkspaceStep.LOG_ANALYTICS_RESOURCE_KEY,
            buildLandingZoneResource()));
    var mockDataExport = mock(DataExport.class);
    when(mockResourceNameGenerator.nextName(anyInt())).thenReturn("fake");
    when(mockStorageHelper.createLogAnalyticsDataExport(
            anyString(), anyString(), anyString(), anyList(), anyString()))
        .thenReturn(mockDataExport);
    when(mockStorageHelper.getResourceGroupRegion(anyString())).thenReturn("eastus");
    var step =
        new ConnectLongTermLogStorageStep(
            mockArmManagers,
            mockParametersResolver,
            mockResourceNameGenerator,
            mockStorageHelper,
            List.of("FakeTableName"),
            Map.of("eastus", "exampleaccount"));

    var result = step.doStep(mockFlightContext);

    assertThat(result.isSuccess(), equalTo(true));
  }

  @Test
  void doStep_failureNoMatchingStorageAcct() throws InterruptedException {
    setupFlightContext(
        mockFlightContext,
        Map.of(
            LandingZoneFlightMapKeys.BILLING_PROFILE,
            new ProfileModel().id(UUID.randomUUID()),
            LandingZoneFlightMapKeys.LANDING_ZONE_ID,
            LANDING_ZONE_ID),
        Map.of(
            GetManagedResourceGroupInfo.TARGET_MRG_KEY,
            new TargetManagedResourceGroup("fake_mrg", "fake_mrg_region"),
            CreateLogAnalyticsWorkspaceStep.LOG_ANALYTICS_RESOURCE_KEY,
            buildLandingZoneResource()));

    when(mockStorageHelper.getResourceGroupRegion(anyString())).thenReturn("eastus");
    var step =
        new ConnectLongTermLogStorageStep(
            mockArmManagers,
            mockParametersResolver,
            mockResourceNameGenerator,
            mockStorageHelper,
            List.of("FakeTableName"),
            Map.of("westus", "exampleaccount"));

    assertThrows(MissingRequiredFieldsException.class, () -> step.doStep(mockFlightContext));
  }

  @Test
  void deleteResource_success() {
    var step =
        new ConnectLongTermLogStorageStep(
            mockArmManagers,
            mockParametersResolver,
            mockResourceNameGenerator,
            mockStorageHelper,
            List.of("FakeTableName"),
            Map.of());

    step.deleteResource("fake_resource");

    verify(mockStorageHelper).deleteDataExport(anyString());
  }
}