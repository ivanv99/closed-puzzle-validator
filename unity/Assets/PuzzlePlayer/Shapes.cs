using System.Collections.Generic;
using UnityEngine;

/// <summary>
/// Sprites for the <c>shape</c> hints of PT/OT (see the Shape definition in the JSON Schema):
/// circle, square, triangle, diamond, heart, polygon-N, star-N. Each shape is an outline in
/// a unit square, rasterised once with a signed-distance edge for anti-aliasing.
/// </summary>
public static class Shapes
{
    private const int Size = 128;
    private const float R = 0.47f;

    private static readonly Dictionary<string, Sprite> Cache = new Dictionary<string, Sprite>();

    /// <summary>The sprite for a shape hint, or null when there is no hint or it is not recognised.</summary>
    public static Sprite Get(string shape)
    {
        if (string.IsNullOrEmpty(shape)) return null;
        if (Cache.TryGetValue(shape, out Sprite sprite)) return sprite;
        Vector2[] outline = Outline(shape);
        sprite = outline == null ? null : Rasterize(outline);
        if (sprite == null) Debug.LogWarning("Unknown shape '" + shape + "'.");
        Cache[shape] = sprite;
        return sprite;
    }

    private static Vector2[] Outline(string shape)
    {
        switch (shape)
        {
            case "circle": return Regular(48, R, 90);
            case "square": return Regular(4, 0.38f * Mathf.Sqrt(2), 45);
            case "triangle": return Regular(3, R, 90);
            case "diamond": return Regular(4, R, 90);
            case "heart": return Heart();
        }
        string[] parts = shape.Split('-');
        if (parts.Length != 2 || !int.TryParse(parts[1], out int n) || n < 3) return null;
        switch (parts[0])
        {
            // vertex up, so polygon-N lines up with the points of star-N
            case "polygon": return Regular(n, R, 90);
            case "star": return Star(n, R, R * (n <= 5 ? 0.45f : 0.6f));
            default: return null;
        }
    }

    private static Vector2[] Regular(int n, float radius, float startDeg)
    {
        var v = new Vector2[n];
        for (int i = 0; i < n; i++) v[i] = Polar(radius, startDeg + i * 360f / n);
        return Centre(v);
    }

    private static Vector2[] Star(int points, float outer, float inner)
    {
        var v = new Vector2[2 * points];
        for (int i = 0; i < v.Length; i++) v[i] = Polar(i % 2 == 0 ? outer : inner, 90 + i * 180f / points);
        return Centre(v);
    }

    private static Vector2[] Heart()
    {
        const int n = 64;
        const float scale = 0.94f / 32;
        var v = new Vector2[n];
        for (int i = 0; i < n; i++)
        {
            float t = 2 * Mathf.PI * i / n;
            float x = 16 * Mathf.Pow(Mathf.Sin(t), 3);
            float y = 13 * Mathf.Cos(t) - 5 * Mathf.Cos(2 * t) - 2 * Mathf.Cos(3 * t) - Mathf.Cos(4 * t);
            v[i] = new Vector2(x, y) * scale;
        }
        return Centre(v);
    }

    private static Vector2 Polar(float r, float deg) =>
        new Vector2(r * Mathf.Cos(deg * Mathf.Deg2Rad), r * Mathf.Sin(deg * Mathf.Deg2Rad));

    /// <summary>Centres the bounding box, so that e.g. a triangle does not sit high in its slot.</summary>
    private static Vector2[] Centre(Vector2[] v)
    {
        Vector2 min = v[0], max = v[0];
        foreach (Vector2 p in v)
        {
            min = Vector2.Min(min, p);
            max = Vector2.Max(max, p);
        }
        Vector2 shift = (min + max) / 2;
        for (int i = 0; i < v.Length; i++) v[i] -= shift;
        return v;
    }

    private static Sprite Rasterize(Vector2[] poly)
    {
        var tex = new Texture2D(Size, Size, TextureFormat.RGBA32, false)
        {
            filterMode = FilterMode.Bilinear,
            wrapMode = TextureWrapMode.Clamp,
        };
        var px = new Color32[Size * Size];
        for (int y = 0; y < Size; y++)
        for (int x = 0; x < Size; x++)
        {
            var p = new Vector2((x + 0.5f) / Size - 0.5f, (y + 0.5f) / Size - 0.5f);
            float d = EdgeDistance(poly, p) * Size; // in pixels
            if (!Inside(poly, p)) d = -d;
            px[y * Size + x] = new Color32(255, 255, 255, (byte)(Mathf.Clamp01(d + 0.5f) * 255));
        }
        tex.SetPixels32(px);
        tex.Apply();
        return Sprite.Create(tex, new Rect(0, 0, Size, Size), new Vector2(0.5f, 0.5f), Size);
    }

    private static bool Inside(Vector2[] poly, Vector2 p)
    {
        bool inside = false;
        for (int i = 0, j = poly.Length - 1; i < poly.Length; j = i++)
        {
            Vector2 a = poly[i], b = poly[j];
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x)
                inside = !inside;
        }
        return inside;
    }

    private static float EdgeDistance(Vector2[] poly, Vector2 p)
    {
        float best = float.MaxValue;
        for (int i = 0, j = poly.Length - 1; i < poly.Length; j = i++)
        {
            Vector2 a = poly[j], ab = poly[i] - a;
            float t = Mathf.Clamp01(Vector2.Dot(p - a, ab) / ab.sqrMagnitude);
            best = Mathf.Min(best, (a + t * ab - p).sqrMagnitude);
        }
        return Mathf.Sqrt(best);
    }
}
