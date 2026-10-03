param(
    [string]$BackendDirectory = (Join-Path $PSScriptRoot '..\backend'),
    [int]$Port = 8092,
    [string]$EmbeddingModel = 'qwen3.7-text-embedding',
    [string]$EmbeddingBaseUrl = 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    [string]$RerankModel = 'qwen3.7-text-rerank',
    [string]$RerankUrl = 'https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank',
    [string]$ChatModel = 'glm-5.3'
)
$ErrorActionPreference = 'Stop'
$backendPath = (Resolve-Path -LiteralPath $BackendDirectory).Path
if (!(Test-Path -LiteralPath (Join-Path $backendPath 'pom.xml'))) { throw 'Backend pom.xml not found' }
$names = @('JAVA_HOME','PATH','MAVEN_OPTS','SPRING_PROFILES_ACTIVE','SERVER_PORT','AI_DB_URL',
    'AI_MODE','AI_API_KEY','AI_BASE_URL','AI_MODEL','AI_EMBEDDING_API_KEY','AI_EMBEDDING_MODEL',
    'AI_EMBEDDING_BASE_URL','AI_RERANK_API_KEY','AI_RERANK_MODEL','AI_RERANK_URL','AI_QDRANT_COLLECTION')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    if ([string]::IsNullOrWhiteSpace($env:AI_EMBEDDING_API_KEY)) {
        if (![string]::IsNullOrWhiteSpace($env:AI_API_KEY)) { $env:AI_EMBEDDING_API_KEY = $env:AI_API_KEY }
        else {
            $secret = Read-Host 'Bailian API key (hidden, process memory only)' -AsSecureString
            $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
            try { $env:AI_EMBEDDING_API_KEY = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
            finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $secret.Dispose() }
        }
    }
    if ([string]::IsNullOrWhiteSpace($env:AI_EMBEDDING_API_KEY)) { throw 'An API key is required' }
    $env:JAVA_HOME = 'E:\JDK17\jdk-17.0.1'
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
    $env:MAVEN_OPTS = '-Xmx384m -XX:+UseSerialGC'
    $env:SPRING_PROFILES_ACTIVE = 'rag-live'
    $env:SERVER_PORT = "$Port"
    $env:AI_DB_URL = 'jdbc:h2:file:./data/ai-hospital-rag-validation;MODE=MySQL;DATABASE_TO_LOWER=TRUE'
    $env:AI_MODE = 'openai-compatible'
    if ([string]::IsNullOrWhiteSpace($env:AI_API_KEY)) { $env:AI_API_KEY = $env:AI_EMBEDDING_API_KEY }
    $env:AI_MODEL = $ChatModel
    $env:AI_BASE_URL = $EmbeddingBaseUrl
    $env:AI_EMBEDDING_MODEL = $EmbeddingModel
    $env:AI_EMBEDDING_BASE_URL = $EmbeddingBaseUrl
    $env:AI_RERANK_API_KEY = $env:AI_EMBEDDING_API_KEY
    $env:AI_RERANK_MODEL = $RerankModel
    $env:AI_RERANK_URL = $RerankUrl
    $env:AI_QDRANT_COLLECTION = 'ai_hospital_knowledge_v2'
    Write-Host "Starting isolated validation service on port $Port; existing service/data are preserved."
    Push-Location $backendPath
    try {
        & mvn spring-boot:run '-Dspring-boot.run.jvmArguments=-Xms32m -Xmx384m -XX:+UseSerialGC'
        if ($LASTEXITCODE -ne 0) { throw "Backend exited with code $LASTEXITCODE" }
    } finally { Pop-Location }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
