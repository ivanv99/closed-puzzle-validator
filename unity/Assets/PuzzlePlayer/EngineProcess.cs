using System;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading.Tasks;
using UnityEngine;

/// <summary>
/// Starts the Java engine (<c>java -jar … serve</c>) as a child process and
/// stops it together with the player. The engine prints <c>LISTENING &lt;port&gt;</c>
/// once it is ready; <c>--parent-pid</c> makes it exit on its own if the player
/// crashes.
/// </summary>
public sealed class EngineProcess : IDisposable
{
    public const string JarName = "closed-puzzle-validator-1.0-SNAPSHOT.jar";

    private Process process;
    private readonly StringBuilder stderr = new StringBuilder();

    public int Port { get; private set; }

    /// <summary>
    /// StreamingAssets/Engine in a build; the Maven output folder of the repo in the Editor
    /// (so a fresh <c>mvn package</c> is picked up without copying).
    /// </summary>
    public static string FindJar()
    {
        string streaming = Path.Combine(Application.streamingAssetsPath, "Engine", JarName);
        string maven = Path.GetFullPath(Path.Combine(Application.dataPath, "..", "..", "target", JarName));
        string[] candidates = Application.isEditor ? new[] { maven, streaming } : new[] { streaming, maven };
        foreach (string c in candidates)
            if (File.Exists(c)) return c;
        throw new FileNotFoundException(
            "Engine jar not found. Run `mvn package` in the repository root. Looked in:\n  " +
            string.Join("\n  ", candidates));
    }

    private static string FindJava()
    {
        string home = Environment.GetEnvironmentVariable("JAVA_HOME");
        if (!string.IsNullOrEmpty(home))
        {
            string exe = Path.Combine(home, "bin", Application.platform is RuntimePlatform.WindowsEditor
                or RuntimePlatform.WindowsPlayer ? "java.exe" : "java");
            if (File.Exists(exe)) return exe;
        }
        return "java";
    }

    public async Task StartAsync(TimeSpan timeout)
    {
        string jar = FindJar();
        int parentPid = Process.GetCurrentProcess().Id;
        var psi = new ProcessStartInfo(FindJava(), $"-jar \"{jar}\" serve --port 0 --parent-pid {parentPid}")
        {
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true,
        };

        try
        {
            process = Process.Start(psi);
        }
        catch (Win32Exception e)
        {
            throw new InvalidOperationException(
                "Java was not found. Install Java 21+ and add it to PATH or set JAVA_HOME. (" + e.Message + ")");
        }

        process.ErrorDataReceived += (_, e) =>
        {
            if (e.Data != null) lock (stderr) stderr.AppendLine(e.Data);
        };
        process.BeginErrorReadLine();

        Task<string> firstLine = process.StandardOutput.ReadLineAsync();
        if (await Task.WhenAny(firstLine, Task.Delay(timeout)) != firstLine)
        {
            Dispose();
            throw new TimeoutException("The engine did not start within " + timeout.TotalSeconds + " s.");
        }

        string line = firstLine.Result;
        if (line == null || !line.StartsWith("LISTENING "))
        {
            await Task.Delay(200); // let stderr arrive
            string errors;
            lock (stderr) errors = stderr.ToString();
            Dispose();
            throw new InvalidOperationException("The engine failed to start.\n" + line + "\n" + errors);
        }
        Port = int.Parse(line.Substring("LISTENING ".Length).Trim());
    }

    public void Dispose()
    {
        if (process == null) return;
        try
        {
            if (!process.HasExited) process.Kill();
        }
        catch (InvalidOperationException)
        {
            // already gone
        }
        process.Dispose();
        process = null;
    }
}
