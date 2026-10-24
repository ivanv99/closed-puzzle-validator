using System;
using System.Linq;
using System.Net.Http;
using System.Text;
using System.Threading.Tasks;
using Newtonsoft.Json.Linq;

/// <summary>
/// Thin wrapper over the engine's HTTP/JSON protocol (see the repository README).
/// Every call returns the parsed JSON body; HTTP errors become <see cref="EngineError"/>.
/// A rejected move is not an error: it comes back as <c>accepted: false</c>.
/// </summary>
public sealed class EngineClient : IDisposable
{
    private readonly HttpClient http;

    public EngineClient(int port)
    {
        http = new HttpClient(new HttpClientHandler { UseProxy = false })
        {
            BaseAddress = new Uri($"http://127.0.0.1:{port}/"),
            Timeout = TimeSpan.FromSeconds(10),
        };
    }

    public async Task<string[]> ExamplesAsync() =>
        (await SendAsync(HttpMethod.Get, "examples", null)).Select(t => (string)t).ToArray();

    public async Task<JObject> NewGameFromExampleAsync(string name) =>
        (JObject)await SendAsync(HttpMethod.Post, "games?example=" + Uri.EscapeDataString(name), null);

    public async Task<JObject> NewGameAsync(string modelJson) =>
        (JObject)await SendAsync(HttpMethod.Post, "games", modelJson);

    public async Task<JObject> PerformAsync(string session, string action, JObject parameters) =>
        (JObject)await SendAsync(HttpMethod.Post, $"games/{session}/actions",
            new JObject { ["action"] = action, ["params"] = parameters }.ToString());

    public async Task<JObject> AdvanceTimeAsync(string session, long ms) =>
        (JObject)await SendAsync(HttpMethod.Post, $"games/{session}/time", new JObject { ["ms"] = ms }.ToString());

    public Task EndGameAsync(string session) => SendAsync(HttpMethod.Delete, $"games/{session}", null);

    private async Task<JToken> SendAsync(HttpMethod method, string path, string body)
    {
        using var request = new HttpRequestMessage(method, path);
        if (body != null) request.Content = new StringContent(body, Encoding.UTF8, "application/json");
        using HttpResponseMessage response = await http.SendAsync(request);
        string text = await response.Content.ReadAsStringAsync();
        JToken json = string.IsNullOrEmpty(text) ? null : JToken.Parse(text);
        if (!response.IsSuccessStatusCode) throw new EngineError((int)response.StatusCode, json);
        return json;
    }

    public void Dispose() => http.Dispose();
}

public sealed class EngineError : Exception
{
    public int Status { get; }

    public EngineError(int status, JToken body) : base(Describe(status, body))
    {
        Status = status;
    }

    private static string Describe(int status, JToken body)
    {
        var sb = new StringBuilder();
        sb.Append((string)body?["error"] ?? "HTTP " + status);
        if (body?["issues"] is JArray issues)
            foreach (JToken i in issues.Where(i => (string)i["severity"] == "ERROR"))
                sb.Append("\n• ").Append((string)i["code"]).Append(": ").Append((string)i["message"]);
        return sb.ToString();
    }
}
