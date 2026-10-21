package dev.notypie.application.configurations

fun createAppConfigWithSecrets(
    slackToken: String = "xoxb-fixture-token",
    slackAppToken: String = "xapp-fixture-app-token",
    signingSecret: String = "fixture-signing-secret",
    mcpSigningSecret: String = "fixture-mcp-secret",
    githubToken: String = "ghp_fixture_token",
    nvdApiKey: String = "fixture-nvd-key",
    sidecarBearerSecret: String = "fixture-sidecar-bearer",
): AppConfig =
    AppConfig(
        api = AppConfig.Api(token = slackToken, appToken = slackAppToken, signingSecret = signingSecret),
        mcp = AppConfig.Mcp(signingSecret = mcpSigningSecret),
        cve =
            AppConfig.Cve(
                github = AppConfig.Cve.Github(token = githubToken),
                nvd = AppConfig.Cve.Nvd(apiKey = nvdApiKey),
            ),
        agent = AppConfig.Agent(sidecar = AppConfig.Agent.Sidecar(bearerSecret = sidecarBearerSecret)),
    )

fun createOutboxRetentionAppConfig(days: Long = 7L, batchSize: Int = 50): AppConfig =
    AppConfig(
        outbox =
            AppConfig.Outbox(
                retention = AppConfig.Outbox.Retention(days = days, batchSize = batchSize),
            ),
    )

fun createAgentRetentionAppConfig(
    askTurnDays: Long = 14L,
    historyDays: Long = 180L,
    batchSize: Int = 1_000,
): AppConfig =
    AppConfig(
        agent =
            AppConfig.Agent(
                retention =
                    AppConfig.Agent.Retention(
                        askTurnDays = askTurnDays,
                        historyDays = historyDays,
                        batchSize = batchSize,
                    ),
            ),
    )
