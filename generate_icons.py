import os

icons = {
    "0_8x": """M6,13a3,3 0 1,1 0,-6a3,3 0 1,1 0,6 M6,22a3,3 0 1,1 0,-6a3,3 0 1,1 0,6 M11,19h2v2h-2z M17,8l5,8 M22,8l-5,8 M5,10h2v-2h-2 M5,19h2v-2h-2""",
    "1x": """M9,8l-3,2v2h2v10h2V8z M17,10l4,10 M21,10l-4,10""",
    "1_25x": """M4,5l-2,1v1h1v8h1V5 M7,15h1v1h-1 M11,5h3c1,0 1,2 0,2h-2v1h2c1,0 1,2 0,2h-4v-1l2,-3c1,0 1,-1 0,-1 M17,5h4v1h-3v1h2c1,0 1,2 0,2h-3v-1h2v-1h-2 M22,10l2,5 M24,10l-2,5""",
    "1_5x": """M5,5l-2,1v1h1v8h1V5 M8,15h1v1h-1 M12,5h4v1h-3v1h2c1,0 1,2 0,2h-3v-1h2v-1h-2 M20,10l3,5 M23,10l-3,5""",
    "2x": """M6,5h4c1,0 1,2 0,2h-2v1h2c1,0 1,2 0,2h-4v-1l2,-3c1,0 1,-1 0,-1 M15,8l5,8 M20,8l-5,8""",
}

template = """<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24.0"
    android:viewportHeight="24.0">
    <path
        android:strokeColor="#FFFFFF"
        android:strokeWidth="2"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:pathData="{path}"/>
</vector>
"""

os.makedirs("app/src/main/res/drawable", exist_ok=True)
for name, p in icons.items():
    with open(f"app/src/main/res/drawable/ic_speed_{name}.xml", "w") as f:
        f.write(template.replace("{path}", p))

