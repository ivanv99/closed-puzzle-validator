using System.IO;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;

/// <summary>
/// One-time project setup, so that cloning the repo and opening <c>unity/</c> is enough:
/// creates the PuzzlePlayer scene and registers it in the build settings, and offers a
/// menu item that bundles the engine jar for stand-alone builds.
/// </summary>
public static class ProjectSetup
{
    private const string ScenePath = "Assets/Scenes/PuzzlePlayer.unity";

    [InitializeOnLoadMethod]
    private static void EnsureScene()
    {
        EditorApplication.delayCall += () =>
        {
            if (File.Exists(ScenePath) || EditorApplication.isPlayingOrWillChangePlaymode) return;
            CreateScene();
        };
    }

    [MenuItem("Puzzle Player/Create Scene")]
    public static void CreateScene()
    {
        if (!EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo()) return;

        var scene = EditorSceneManager.NewScene(NewSceneSetup.EmptyScene, NewSceneMode.Single);
        var cameraObject = new GameObject("Main Camera") { tag = "MainCamera" };
        var cam = cameraObject.AddComponent<Camera>();
        cam.orthographic = true;
        cam.clearFlags = CameraClearFlags.SolidColor;
        cam.backgroundColor = new Color(0.12f, 0.13f, 0.16f);
        cameraObject.transform.position = new Vector3(0, 0, -10);
        cameraObject.AddComponent<AudioListener>();
        new GameObject("Puzzle Player").AddComponent<PuzzlePlayer>();

        Directory.CreateDirectory(Path.GetDirectoryName(ScenePath));
        EditorSceneManager.SaveScene(scene, ScenePath);
        EditorBuildSettings.scenes = new[] { new EditorBuildSettingsScene(ScenePath, true) };
        Debug.Log("Puzzle Player: created " + ScenePath + " and added it to the build settings.");
    }

    /// <summary>A build has no repo next to it, so the jar must travel in StreamingAssets.</summary>
    [MenuItem("Puzzle Player/Copy Engine Jar to StreamingAssets")]
    public static void CopyJar()
    {
        string jar = Path.GetFullPath(Path.Combine(Application.dataPath, "..", "..", "target", EngineProcess.JarName));
        if (!File.Exists(jar))
        {
            EditorUtility.DisplayDialog("Puzzle Player",
                "Не е намерен\n" + jar + "\n\nПусни `mvn package` в корена на repo-то.", "OK");
            return;
        }
        string dir = Path.Combine(Application.streamingAssetsPath, "Engine");
        Directory.CreateDirectory(dir);
        File.Copy(jar, Path.Combine(dir, EngineProcess.JarName), true);
        AssetDatabase.Refresh();
        Debug.Log("Puzzle Player: copied the engine to " + dir);
    }
}
