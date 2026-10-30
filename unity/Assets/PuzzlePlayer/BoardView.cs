using System.Collections.Generic;
using System.Linq;
using Newtonsoft.Json.Linq;
using UnityEngine;

/// <summary>
/// Generates the scene from the model (P, PT, O, OT) and keeps it in sync with
/// the state sent by the engine. Nothing here is game-specific:
/// <list type="bullet">
///   <item>positions → slots at (x, −y); invisible positions get no slot but still hold objects;</item>
///   <item>objects → pieces coloured by type (or by their <c>color</c>), labelled with their
///         <c>title</c>, or with the type name when there is more than one type;</item>
///   <item>a <c>shape</c> on PT/OT (see <see cref="Shapes"/>) replaces the rounded square and the type label;</item>
///   <item><c>visible: false</c> → face down (grey, no label); <c>focused: true</c> → highlighted;</item>
///   <item><c>rotation</c> and <c>size</c> are applied as transform rotation and scale.</item>
/// </list>
/// </summary>
public sealed class BoardView : MonoBehaviour
{
    private const float Spacing = 1.3f;
    private const float SlotSize = 1f;
    private const float PieceSize = 0.8f;

    private static readonly Color[] Palette =
    {
        new Color(0.36f, 0.47f, 0.66f), new Color(0.40f, 0.70f, 0.55f), new Color(0.85f, 0.45f, 0.40f),
        new Color(0.62f, 0.50f, 0.78f), new Color(0.90f, 0.62f, 0.30f), new Color(0.35f, 0.70f, 0.78f),
        new Color(0.80f, 0.50f, 0.65f), new Color(0.60f, 0.65f, 0.35f),
    };
    private static readonly Color SlotColor = new Color(0.22f, 0.24f, 0.28f);
    private static readonly Color HiddenColor = new Color(0.45f, 0.48f, 0.53f);
    private static readonly Color FocusColor = new Color(1f, 0.84f, 0.29f);
    private static readonly Color LabelColor = new Color(0.08f, 0.08f, 0.1f);

    private sealed class Piece
    {
        public GameObject Go;
        public SpriteRenderer Body;
        public TextMesh Label;
        public string Type;
        public Sprite Shape;
        public string Position;
        public Vector3 Target;
        public Vector3 Scale;
        public bool Dragging;
    }

    private readonly Dictionary<string, Piece> pieces = new Dictionary<string, Piece>();
    private readonly Dictionary<string, Vector3> slots = new Dictionary<string, Vector3>();
    private readonly List<GameObject> spawned = new List<GameObject>();
    private Dictionary<string, int> typeIndex = new Dictionary<string, int>();
    private bool labelByType;
    private Sprite sprite;
    private Font font;

    public Bounds Bounds { get; private set; }

    /// <summary>Piece picked for click-to-move; drawn slightly enlarged.</summary>
    public string Selected { get; set; }

    public void Build(JObject model, JObject state)
    {
        Clear();
        sprite ??= MakeRoundedSquare(64, 12);
        font ??= Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");

        var positionTypes = (JArray)model["PT"];
        Dictionary<string, Sprite> slotShapes = ShapesByType(positionTypes);
        var positions = (JArray)model["P"];
        int index = 0;
        foreach (JObject p in positions)
        {
            string id = (string)p["id"];
            float x = (float?)p["x"] ?? index;
            float y = (float?)p["y"] ?? 0;
            index++;
            var centre = new Vector3(x * Spacing, -y * Spacing, 0);
            slots[id] = centre;

            if (!((bool?)state["positions"]?[id]?["visible"] ?? true)) continue;
            slotShapes.TryGetValue((string)p["type"] ?? "", out Sprite shape);
            SpriteRenderer slot = NewSprite("Slot " + id, centre, SlotSize, SlotColor, 0, shape);
            // a shape already tells the types apart
            string caption = (string)p["title"] ??
                             (shape == null && positionTypes.Count > 1 ? (string)p["type"] : null);
            if (caption != null)
                NewLabel(slot.transform, caption, new Vector3(0, -0.62f, 0), 0.03f, new Color(0.75f, 0.77f, 0.8f), 1);
        }

        typeIndex = model["OT"].Select((t, i) => ((string)t["id"], i)).ToDictionary(t => t.Item1, t => t.i);
        labelByType = typeIndex.Count > 1;
        Dictionary<string, Sprite> pieceShapes = ShapesByType((JArray)model["OT"]);
        foreach (JObject o in model["O"])
        {
            string id = (string)o["id"];
            string type = (string)o["type"];
            pieceShapes.TryGetValue(type ?? "", out Sprite shape);
            SpriteRenderer body = NewSprite("Object " + id, Vector3.zero, PieceSize, Color.white, 10, null);
            TextMesh label = NewLabel(body.transform, "", Vector3.zero, 0.05f, LabelColor, 11);
            pieces[id] = new Piece { Go = body.gameObject, Body = body, Label = label, Type = type, Shape = shape };
        }

        Bounds = slots.Values.Aggregate(new Bounds(slots.Values.First(), Vector3.zero), (b, c) =>
        {
            b.Encapsulate(c);
            return b;
        });
        Bounds = new Bounds(Bounds.center, Bounds.size + new Vector3(SlotSize, SlotSize, 0));
        Apply(state, snap: true);
    }

    public void Apply(JObject state, bool snap = false)
    {
        var stacked = new Dictionary<string, int>();
        foreach (KeyValuePair<string, Piece> entry in pieces)
        {
            Piece piece = entry.Value;
            var o = (JObject)state["objects"][entry.Key];
            piece.Position = (string)o["position"];
            piece.Go.SetActive(piece.Position != null);
            if (piece.Position == null) continue;

            stacked.TryGetValue(piece.Position, out int n);
            stacked[piece.Position] = n + 1;
            piece.Target = slots[piece.Position] + new Vector3(0.12f * n, -0.12f * n, -0.01f * n);

            bool visible = (bool?)o["visible"] ?? true;
            bool focused = (bool?)o["focused"] ?? false;
            Color baseColor = ColorUtility.TryParseHtmlString((string)o["color"] ?? "", out Color c) ? c : TypeColor(piece.Type);
            piece.Body.color = !visible ? HiddenColor : focused ? FocusColor : baseColor;
            // face down keeps the plain card, otherwise the shape would give the type away
            piece.Body.sprite = visible && piece.Shape != null ? piece.Shape : sprite;
            string typeLabel = labelByType && piece.Shape == null ? piece.Type : "";
            SetLabel(piece.Label, visible ? (string)o["title"] ?? typeLabel : "");

            piece.Scale = Vector3.one * PieceSize * ((float?)o["size"] ?? 1f);
            piece.Go.transform.rotation = Quaternion.Euler(0, 0, -((float?)o["rotation"] ?? 0f));
            if (snap)
            {
                piece.Go.transform.position = piece.Target;
                piece.Go.transform.localScale = piece.Scale;
            }
        }
    }

    /* ----- pointer support ------------------------------------------------ */

    /// <summary>Topmost piece under the point, or null.</summary>
    public string PieceAt(Vector2 world)
    {
        return pieces
            .Where(p => p.Value.Go.activeSelf && Contains(p.Value.Go.transform.position, PieceSize, world))
            .OrderByDescending(p => p.Value.Body.sortingOrder)
            .ThenBy(p => p.Value.Go.transform.position.z)
            .Select(p => p.Key)
            .FirstOrDefault();
    }

    /// <summary>Position under the point (invisible positions included), or null.</summary>
    public string SlotAt(Vector2 world)
    {
        return slots.Where(s => Contains(s.Value, SlotSize, world)).Select(s => s.Key).FirstOrDefault();
    }

    public string LocationOf(string piece) => pieces[piece].Position;

    public void Drag(string id, Vector2 world)
    {
        Piece p = pieces[id];
        p.Dragging = true;
        p.Go.transform.position = new Vector3(world.x, world.y, -1);
        SetSorting(p, 100);
    }

    public void EndDrag(string id)
    {
        Piece p = pieces[id];
        p.Dragging = false;
        SetSorting(p, 10);
    }

    private void Update()
    {
        float k = 1f - Mathf.Exp(-14f * Time.deltaTime);
        foreach (KeyValuePair<string, Piece> entry in pieces)
        {
            Piece p = entry.Value;
            if (!p.Go.activeSelf) continue;
            if (!p.Dragging) p.Go.transform.position = Vector3.Lerp(p.Go.transform.position, p.Target, k);
            Vector3 scale = p.Dragging || entry.Key == Selected ? p.Scale * 1.12f : p.Scale;
            p.Go.transform.localScale = Vector3.Lerp(p.Go.transform.localScale, scale, k);
        }
    }

    /* ----- helpers --------------------------------------------------------- */

    public void Clear()
    {
        foreach (GameObject go in spawned) Destroy(go);
        spawned.Clear();
        pieces.Clear();
        slots.Clear();
        Selected = null;
        Bounds = default;
    }

    private static Dictionary<string, Sprite> ShapesByType(JArray types) =>
        types.Where(t => (string)t["shape"] != null)
            .ToDictionary(t => (string)t["id"], t => Shapes.Get((string)t["shape"]));

    private Color TypeColor(string type) =>
        typeIndex.TryGetValue(type ?? "", out int i) ? Palette[i % Palette.Length] : Palette[0];

    private static bool Contains(Vector3 centre, float size, Vector2 point) =>
        Mathf.Abs(point.x - centre.x) <= size / 2 && Mathf.Abs(point.y - centre.y) <= size / 2;

    private static void SetSorting(Piece p, int order)
    {
        p.Body.sortingOrder = order;
        p.Label.GetComponent<MeshRenderer>().sortingOrder = order + 1;
    }

    private static void SetLabel(TextMesh label, string text)
    {
        label.text = text;
        // Long labels (type names) are shrunk to fit the piece.
        label.characterSize = 0.05f * Mathf.Min(1f, 3f / Mathf.Max(1, text.Length));
    }

    private SpriteRenderer NewSprite(string name, Vector3 position, float size, Color color, int order, Sprite shape)
    {
        var go = new GameObject(name);
        go.transform.SetParent(transform, false);
        go.transform.position = position;
        go.transform.localScale = Vector3.one * size;
        var sr = go.AddComponent<SpriteRenderer>();
        sr.sprite = shape != null ? shape : sprite;
        sr.color = color;
        sr.sortingOrder = order;
        spawned.Add(go);
        return sr;
    }

    private TextMesh NewLabel(Transform parent, string text, Vector3 offset, float charSize, Color color, int order)
    {
        var go = new GameObject("Label");
        go.transform.SetParent(parent, false);
        go.transform.localPosition = offset / parent.localScale.x;
        var tm = go.AddComponent<TextMesh>();
        tm.font = font;
        tm.fontSize = 64;
        tm.characterSize = charSize;
        tm.anchor = TextAnchor.MiddleCenter;
        tm.alignment = TextAlignment.Center;
        tm.color = color;
        tm.text = text;
        var mr = go.GetComponent<MeshRenderer>();
        mr.sharedMaterial = font.material;
        mr.sortingOrder = order;
        return tm;
    }

    private static Sprite MakeRoundedSquare(int size, int radius)
    {
        var tex = new Texture2D(size, size, TextureFormat.RGBA32, false)
        {
            filterMode = FilterMode.Bilinear,
            wrapMode = TextureWrapMode.Clamp,
        };
        var px = new Color32[size * size];
        for (int y = 0; y < size; y++)
        for (int x = 0; x < size; x++)
        {
            float dx = Mathf.Max(radius - x - 0.5f, x + 0.5f - (size - radius), 0);
            float dy = Mathf.Max(radius - y - 0.5f, y + 0.5f - (size - radius), 0);
            float alpha = Mathf.Clamp01(radius - Mathf.Sqrt(dx * dx + dy * dy) + 0.5f);
            px[y * size + x] = new Color32(255, 255, 255, (byte)(alpha * 255));
        }
        tex.SetPixels32(px);
        tex.Apply();
        return Sprite.Create(tex, new Rect(0, 0, size, size), new Vector2(0.5f, 0.5f), size);
    }
}
