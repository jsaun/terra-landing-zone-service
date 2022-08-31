package bio.terra.landingzone.library;

import bio.terra.landingzone.library.configuration.LandingZoneAzureConfiguration;
import bio.terra.landingzone.library.landingzones.management.LandingZoneManager;
import bio.terra.landingzone.model.AzureCloudContext;
import com.azure.core.credential.TokenCredential;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.identity.ClientSecretCredentialBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class LandingZoneManagerProvider {
  private final LandingZoneAzureConfiguration azureConfiguration;

  @Autowired
  public LandingZoneManagerProvider(LandingZoneAzureConfiguration azureConfiguration) {
    this.azureConfiguration = azureConfiguration;
  }

  public LandingZoneManager createLandingZoneManager(AzureCloudContext azureCloudContext) {
    var azureProfile =
        new AzureProfile(
            azureCloudContext.getAzureTenantId(),
            azureCloudContext.getAzureSubscriptionId(),
            AzureEnvironment.AZURE);
    return LandingZoneManager.createLandingZoneManager(
        buildTokenCredential(), azureProfile, azureCloudContext.getAzureResourceGroupId());
  }

  private TokenCredential buildTokenCredential() {
    return new ClientSecretCredentialBuilder()
        .clientId(azureConfiguration.getManagedAppClientId())
        .clientSecret(azureConfiguration.getManagedAppClientSecret())
        .tenantId(azureConfiguration.getManagedAppTenantId())
        .build();
  }
}