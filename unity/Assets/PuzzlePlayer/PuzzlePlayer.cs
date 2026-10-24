using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using Newtonsoft.Json.Linq;
using UnityEngine;

/// <summary>
/// The whole client: starts the Java engine, lets the player pick a model, renders it
/// with <see cref="BoardView"/> and turns pointer input into the actions declared in A.
/// The client never decides whether a move is legal: it sends the action and shows
/// whatever state the engine returns.
/// <list type="bullet">
///   <item>click on an object → every player action of a click kind, with <c>target</c>,
///         until one is accepted;</item>
///   <item>drag an object onto a position, or click it and then the position →
///         every player <c>move</c> action, with <c>obj</c> and <c>dst</c>;</item>
///   <item>delayed effects: while the engine reports a pending timer, the real time
///         elapsed is sent with <c>/time</c> before each move and when the timer is due.</item>
/// </list>
/// </summary>
public sealed class PuzzlePlayer : MonoBehaviour
{
    private enum Mode { Starting, Menu, Playing, Failed }

    private static readonly string[] ClickKinds = { "click", "doubleClick", "flip", "selectSequence" };
    private const float HudHeight = 44f;

    /// <summary>Makes any scene playable: pressing Play in an empty scene is enough.</summary>
    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
    private static void Bootstrap()
    {
        if (FindAnyObjectByType<PuzzlePlayer>() == null)
            new GameObject("Puzzle Player").AddComponent<PuzzlePlayer>();
    }

    private EngineProcess engine;
    private EngineClient client;
    private BoardView board;
    private Camera cam;
    private AudioSource audioSource;
    private AudioClip chime;

    private Mode mode = Mode.Starting;
    private string failure;
    private string[] examples = Array.Empty<string>();
    private string modelPath = "";

    // current game
    private Func<Task<JObject>> source; // how to (re)create it, for "Restart"
    private string session;
    private string title;
    private List<string> clickActions = new List<string>();
    private List<string> moveActions = new List<string>();
    private bool finished;
    private JToken result;
    private bool busy;

    // engine clock: only tracked while a delayed effect is pending
    private bool timerPending;
    private double timerDue;
    private double lastSync;

    private string toast;
    private double toastUntil;

    // pointer
    private bool pressing;
    private bool dragging;
    private string pressed;
    private Vector2 pressWorld;

    private GUIStyle titleStyle, labelStyle, buttonStyle, boxStyle, toastStyle;

    private static double Now => Time.realtimeSinceStartupAsDouble;

    private void Start()
    {
        Application.runInBackground = true;

        cam = Camera.main;
        if (cam == null)
        {
            var go = new GameObject("Main Camera") { tag = "MainCamera" };
            cam = go.AddComponent<Camera>();
        }
        cam.orthographic = true;
        cam.clearFlags = CameraClearFlags.SolidColor;
        cam.backgroundColor = new Color(0.12f, 0.13f, 0.16f);
        cam.transform.SetPositionAndRotation(new Vector3(0, 0, -10), Quaternion.identity);
        if (FindAnyObjectByType<AudioListener>() == null) cam.gameObject.AddComponent<AudioListener>();

        audioSource = gameObject.AddComponent<AudioSource>();
        chime = MakeChime();
        board = new GameObject("Board").AddComponent<BoardView>();

        StartEngine();
    }

    private void OnDestroy() => Shutdown();

    /* ----- engine and games ------------------------------------------------ */

    private async void StartEngine()
    {
        mode = Mode.Starting;
        Shutdown();
        try
        {
            engine = new EngineProcess();
            await engine.StartAsync(TimeSpan.FromSeconds(20));
            if (this == null) return;
            client = new EngineClient(engine.Port);
            examples = await client.ExamplesAsync();
            if (this == null) return;
            mode = Mode.Menu;
        }
        catch (Exception e)
        {
            Fail(e);
        }
    }

    private async void NewGame(Func<Task<JObject>> create)
    {
        if (busy) return;
        busy = true;
        try
        {
            JObject created = await create();
            if (this == null) return;
            EndSession();
            source = create;
            session = (string)created["sessionId"];

            var model = (JObject)created["model"];
            title = (string)model["name"] ?? (string)model["id"];
            clickActions = PlayerActions(model, ClickKinds);
            moveActions = PlayerActions(model, "move");
            foreach (JToken w in created["warnings"] ?? new JArray())
                Debug.LogWarning($"[{title}] {(string)w["code"]}: {(string)w["message"]}");

            board.Build(model, (JObject)created["state"]);
            ApplyStatus((JObject)created["status"]);
            pressing = dragging = false;
            mode = Mode.Playing;
        }
        catch (Exception e) when (e is EngineError || e is IOException || e is UnauthorizedAccessException
                                  || e is ArgumentException || e is NotSupportedException)
        {
            ShowToast(e.Message); // a bad model or path: stay where we are
        }
        catch (Exception e)
        {
            Fail(e);
        }
        finally
        {
            busy = false;
        }
    }

    private async void Perform(List<string> actions, JObject parameters)
    {
        if (busy || actions.Count == 0) return;
        busy = true;
        try
        {
            await SyncClock();
            if (finished) return; // a timer may have ended the game
            JObject reply = null;
            foreach (string action in actions)
            {
                reply = await client.PerformAsync(session, action, parameters);
                if ((bool)reply["accepted"]) break;
            }
            if (this == null) return;
            Show(reply);
            if (!(bool)reply["accepted"]) ShowToast((string)reply["message"]);
        }
        catch (EngineError e)
        {
            ShowToast(e.Message);
        }
        catch (Exception e)
        {
            Fail(e);
        }
        finally
        {
            busy = false;
        }
    }

    private async void Tick()
    {
        busy = true;
        try
        {
            await SyncClock();
        }
        catch (Exception e)
        {
            Fail(e);
        }
        finally
        {
            busy = false;
        }
    }

    /// <summary>Moves the engine clock forward by the real time elapsed since the last sync.</summary>
    private async Task SyncClock()
    {
        if (!timerPending) return;
        long ms = (long)Math.Ceiling((Now - lastSync) * 1000);
        if (ms <= 0) return;
        JObject reply = await client.AdvanceTimeAsync(session, ms);
        if (this != null) Show(reply);
    }

    private void Show(JObject step)
    {
        board.Apply((JObject)step["state"]);
        ApplyStatus((JObject)step["status"]);
        foreach (JToken s in step["sounds"] ?? new JArray()) PlaySound((string)s);
    }

    private void ApplyStatus(JObject status)
    {
        finished = (bool)status["finished"];
        result = status["result"];
        long? next = (long?)status["nextTimerInMs"];
        timerPending = next != null && !finished;
        lastSync = Now;
        if (timerPending) timerDue = lastSync + next.Value / 1000.0;
        if (finished) board.Selected = null;
    }

    private void Update()
    {
        if (mode == Mode.Playing && timerPending && !busy && Now >= timerDue) Tick();
    }

    private void LateUpdate()
    {
        if (mode != Mode.Playing) return;
        Bounds b = board.Bounds;
        float fit = Mathf.Max(b.extents.y, b.extents.x / cam.aspect);
        // leave room for the HUD bar at the top
        float hud = HudHeight / 720f;
        cam.orthographicSize = (fit + 0.3f) / (1f - 2f * hud);
        cam.transform.position = new Vector3(b.center.x, b.center.y + cam.orthographicSize * hud, -10);
    }

    private void EndSession()
    {
        if (session == null || client == null) return;
        client.EndGameAsync(session).ContinueWith(t => _ = t.Exception); // best effort
        session = null;
    }

    private void BackToMenu()
    {
        EndSession();
        board.Clear();
        timerPending = finished = false;
        mode = Mode.Menu;
    }

    private void Shutdown()
    {
        session = null; // the engine forgets its games when it stops
        client?.Dispose();
        client = null;
        engine?.Dispose();
        engine = null;
    }

    private void Fail(Exception e)
    {
        Debug.LogException(e);
        if (this == null) return;
        failure = e.Message;
        board.Clear();
        Shutdown();
        mode = Mode.Failed;
    }

    private static List<string> PlayerActions(JObject model, params string[] kinds) =>
        model["A"]
            .Where(a => kinds.Contains((string)a["kind"]) && ((string)a["origin"] ?? "player") == "player")
            .Select(a => (string)a["id"])
            .ToList();

    /* ----- pointer --------------------------------------------------------- */

    private void HandlePointer(Event e)
    {
        if (e.button != 0) return;
        Vector3 screen = new Vector3(e.mousePosition.x, Screen.height - e.mousePosition.y, 0);
        Vector2 world = cam.ScreenToWorldPoint(screen);

        switch (e.type)
        {
            case EventType.MouseDown:
                if (e.mousePosition.y < HudHeight * UiScale || busy || finished) return;
                pressing = true;
                dragging = false;
                pressed = board.PieceAt(world);
                pressWorld = world;
                e.Use();
                break;

            case EventType.MouseDrag:
                if (!pressing || pressed == null || moveActions.Count == 0) return;
                if (!dragging && Vector2.Distance(world, pressWorld) < 0.15f) return;
                dragging = true;
                board.Selected = null;
                board.Drag(pressed, world);
                e.Use();
                break;

            case EventType.MouseUp:
                if (!pressing) return;
                pressing = false;
                if (dragging)
                {
                    dragging = false;
                    board.EndDrag(pressed);
                    string dst = board.SlotAt(world);
                    if (dst != null && dst != board.LocationOf(pressed)) Move(pressed, dst);
                }
                else
                {
                    Click(pressed, board.SlotAt(world));
                }
                e.Use();
                break;
        }
    }

    private void Click(string piece, string slot)
    {
        if (piece != null && clickActions.Count > 0)
        {
            board.Selected = null;
            Perform(clickActions, new JObject { ["target"] = piece });
            return;
        }
        if (moveActions.Count == 0) return;

        // click-to-move: first the object, then the destination
        string selected = board.Selected;
        if (piece != null && piece != selected && (selected == null || slot == board.LocationOf(piece)))
            board.Selected = piece;
        else if (selected != null && slot != null && slot != board.LocationOf(selected))
            Move(selected, slot);
        else
            board.Selected = null;
    }

    private void Move(string piece, string dst)
    {
        board.Selected = null;
        Perform(moveActions, new JObject { ["obj"] = piece, ["dst"] = dst });
    }

    /* ----- GUI ------------------------------------------------------------- */

    private static float UiScale => Mathf.Max(1f, Screen.height / 720f);

    private void OnGUI()
    {
        // board input first, in raw screen coordinates; the HUD band is excluded
        if (mode == Mode.Playing) HandlePointer(Event.current);

        EnsureStyles();
        float s = UiScale;
        GUI.matrix = Matrix4x4.Scale(new Vector3(s, s, 1));
        float w = Screen.width / s, h = Screen.height / s;

        switch (mode)
        {
            case Mode.Starting:
                GUI.Label(new Rect(0, h / 2 - 20, w, 40), "Стартиране на engine-а…", titleStyle);
                break;
            case Mode.Failed:
                DrawFailed(w, h);
                break;
            case Mode.Menu:
                DrawMenu(w, h);
                break;
            case Mode.Playing:
                DrawHud(w);
                if (finished) DrawFinished(w, h);
                break;
        }
        DrawToast(w, h);
        GUI.matrix = Matrix4x4.identity;
    }

    private void DrawMenu(float w, float h)
    {
        GUILayout.BeginArea(new Rect(w / 2 - 200, h * 0.12f, 400, h * 0.85f));
        GUILayout.Label("Closed Puzzle Player", titleStyle);
        GUILayout.Space(16);
        GUI.enabled = !busy;
        foreach (string name in examples)
            if (GUILayout.Button(Pretty(name), buttonStyle, GUILayout.Height(40)))
                NewGame(() => client.NewGameFromExampleAsync(name));

        GUILayout.Space(24);
        GUILayout.Label("Модел от файл (JSON):", labelStyle);
        modelPath = GUILayout.TextField(modelPath, GUILayout.Height(28));
        if (GUILayout.Button("Зареди", buttonStyle, GUILayout.Height(36)))
        {
            string path = modelPath.Trim().Trim('"');
            NewGame(async () => await client.NewGameAsync(File.ReadAllText(path)));
        }
        GUI.enabled = true;
        GUILayout.EndArea();
    }

    private void DrawHud(float w)
    {
        GUI.Box(new Rect(0, 0, w, HudHeight), GUIContent.none, boxStyle);
        GUI.Label(new Rect(12, 0, w - 260, HudHeight), title, labelStyle);
        GUI.enabled = !busy;
        if (GUI.Button(new Rect(w - 236, 6, 110, HudHeight - 12), "Отначало", buttonStyle)) NewGame(source);
        if (GUI.Button(new Rect(w - 118, 6, 110, HudHeight - 12), "Меню", buttonStyle)) BackToMenu();
        GUI.enabled = true;
    }

    private void DrawFinished(float w, float h)
    {
        var r = new Rect(w / 2 - 170, h / 2 - 80, 340, 160);
        GUI.Box(r, GUIContent.none, boxStyle);
        GUI.Label(new Rect(r.x, r.y + 20, r.width, 40), ResultText(), titleStyle);
        GUI.enabled = !busy;
        if (GUI.Button(new Rect(r.x + 30, r.y + 96, 130, 40), "Отначало", buttonStyle)) NewGame(source);
        if (GUI.Button(new Rect(r.x + 180, r.y + 96, 130, 40), "Меню", buttonStyle)) BackToMenu();
        GUI.enabled = true;
    }

    private void DrawFailed(float w, float h)
    {
        var r = new Rect(w / 2 - 300, h / 2 - 140, 600, 280);
        GUI.Box(r, GUIContent.none, boxStyle);
        GUI.Label(new Rect(r.x, r.y + 16, r.width, 36), "Engine-ът не е достъпен", titleStyle);
        GUI.Label(new Rect(r.x + 20, r.y + 64, r.width - 40, 150), failure, toastStyle);
        if (GUI.Button(new Rect(r.x + r.width / 2 - 80, r.y + r.height - 56, 160, 40), "Опитай пак", buttonStyle))
            StartEngine();
    }

    private void DrawToast(float w, float h)
    {
        if (toast == null || Now > toastUntil) return;
        float width = Mathf.Min(560, w - 40);
        float height = toastStyle.CalcHeight(new GUIContent(toast), width - 24) + 20;
        var r = new Rect((w - width) / 2, h - height - 24, width, height);
        GUI.Box(r, GUIContent.none, boxStyle);
        GUI.Label(new Rect(r.x + 12, r.y + 10, r.width - 24, r.height - 20), toast, toastStyle);
    }

    private void ShowToast(string text)
    {
        if (string.IsNullOrEmpty(text)) return;
        toast = text;
        toastUntil = Now + 2.5 + text.Length / 40.0;
    }

    private string ResultText()
    {
        if (result == null || result.Type == JTokenType.Null) return "Край на играта";
        if (result.Type == JTokenType.Boolean) return (bool)result ? "Победа!" : "Загуба";
        return "Резултат: " + result;
    }

    private static string Pretty(string name) =>
        string.Join(" ", name.Split('-').Select(p => p.Length == 0 ? p : char.ToUpper(p[0]) + p.Substring(1)));

    private void EnsureStyles()
    {
        if (titleStyle != null) return;
        titleStyle = new GUIStyle(GUI.skin.label)
        {
            fontSize = 26, fontStyle = FontStyle.Bold, alignment = TextAnchor.MiddleCenter,
            normal = { textColor = Color.white },
        };
        labelStyle = new GUIStyle(GUI.skin.label)
        {
            fontSize = 17, alignment = TextAnchor.MiddleLeft, normal = { textColor = Color.white },
        };
        buttonStyle = new GUIStyle(GUI.skin.button) { fontSize = 16 };
        toastStyle = new GUIStyle(GUI.skin.label)
        {
            fontSize = 15, wordWrap = true, normal = { textColor = Color.white },
        };
        var bg = new Texture2D(1, 1);
        bg.SetPixel(0, 0, new Color(0.05f, 0.06f, 0.08f, 0.92f));
        bg.Apply();
        boxStyle = new GUIStyle(GUI.skin.box) { normal = { background = bg } };
    }

    /* ----- sound ----------------------------------------------------------- */

    /// <summary>Plays Resources/Sounds/&lt;name without extension&gt;, or a short chime if there is none.</summary>
    private void PlaySound(string name)
    {
        AudioClip clip = Resources.Load<AudioClip>("Sounds/" + Path.GetFileNameWithoutExtension(name ?? ""));
        audioSource.PlayOneShot(clip != null ? clip : chime);
    }

    private static AudioClip MakeChime()
    {
        const int rate = 44100;
        int n = (int)(rate * 0.35f);
        var data = new float[n];
        for (int i = 0; i < n; i++)
        {
            float t = i / (float)rate;
            data[i] = 0.25f * Mathf.Exp(-9f * t) *
                      (Mathf.Sin(2 * Mathf.PI * 880 * t) + 0.5f * Mathf.Sin(2 * Mathf.PI * 1320 * t));
        }
        AudioClip clip = AudioClip.Create("Chime", n, 1, rate, false);
        clip.SetData(data, 0);
        return clip;
    }
}
