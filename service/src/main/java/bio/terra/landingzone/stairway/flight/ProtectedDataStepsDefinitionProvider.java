package bio.terra.landingzone.stairway.flight;

import bio.terra.landingzone.common.utils.RetryRules;
import bio.terra.landingzone.library.configuration.LandingZoneProtectedDataConfiguration;
import bio.terra.landingzone.library.landingzones.definition.ArmManagers;
import bio.terra.landingzone.library.landingzones.definition.factories.ParametersResolver;
import bio.terra.landingzone.stairway.flight.create.resource.step.ConnectLongTermLogStorageStep;
import bio.terra.landingzone.stairway.flight.create.resource.step.CreateAksLogSettingsStep;
import bio.terra.landingzone.stairway.flight.create.resource.step.CreateSentinelAlertRulesStep;
import bio.terra.landingzone.stairway.flight.create.resource.step.CreateSentinelRunPlaybookAutomationRule;
import bio.terra.landingzone.stairway.flight.create.resource.step.CreateSentinelStep;
import bio.terra.landingzone.stairway.flight.utils.AlertRulesHelper;
import bio.terra.landingzone.stairway.flight.utils.ProtectedDataAzureStorageHelper;
import bio.terra.stairway.RetryRule;
import bio.terra.stairway.Step;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.tuple.Pair;

public class ProtectedDataStepsDefinitionProvider extends CromwellStepsDefinitionProvider {
  @Override
  public List<Pair<Step, RetryRule>> get(
      ArmManagers armManagers,
      ParametersResolver parametersResolver,
      ResourceNameProvider resourceNameProvider,
      LandingZoneProtectedDataConfiguration landingZoneProtectedDataConfiguration) {
    // inherit all cromwell steps and define specific below
    var protectedDataSteps =
        new ArrayList<>(
            super.get(
                armManagers,
                parametersResolver,
                resourceNameProvider,
                landingZoneProtectedDataConfiguration));

    protectedDataSteps.add(
        Pair.of(
            new ConnectLongTermLogStorageStep(
                armManagers,
                parametersResolver,
                resourceNameProvider,
                new ProtectedDataAzureStorageHelper(armManagers),
                landingZoneProtectedDataConfiguration.getLongTermStorageTableNames(),
                landingZoneProtectedDataConfiguration.getLongTermStorageAccountIds()),
            RetryRules.cloud()));

    protectedDataSteps.add(
        Pair.of(
            new CreateSentinelStep(armManagers, parametersResolver, resourceNameProvider),
            RetryRules.cloud()));

    protectedDataSteps.add(
        Pair.of(
            new CreateSentinelRunPlaybookAutomationRule(
                armManagers,
                parametersResolver,
                resourceNameProvider,
                landingZoneProtectedDataConfiguration),
            RetryRules.cloud()));

    protectedDataSteps.add(
        Pair.of(
            new CreateSentinelAlertRulesStep(
                armManagers,
                parametersResolver,
                resourceNameProvider,
                new AlertRulesHelper(armManagers.securityInsightsManager()),
                landingZoneProtectedDataConfiguration),
            RetryRules.cloudLongRunning()));
    protectedDataSteps.add(
        Pair.of(
            new CreateAksLogSettingsStep(
                armManagers,
                parametersResolver,
                resourceNameProvider,
                landingZoneProtectedDataConfiguration),
            RetryRules.cloud()));

    return protectedDataSteps;
  }
}
