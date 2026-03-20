# GoForIt — Adding a new feature or new code component

This README explains **how to add a new feature or new code component** in the GoForIt project.

---

## Project structure overview

```
com.example.goforitGit/
├── core/           ← shared non-UI code across multiple features (services, API, utilities, etc..)
│   │
│   ├── data/       ← repositories, FirebaseServerApi, etc... 
│   │
│   ├── service/    ← StepService, BleAdvertScanService, etc...
│   │
│   └── util/       ← buckets, schedulers, helpers, etc...
│   
├── navigation/     ← MainActivity + drawer wiring
│   
└── feature/        ← one sub-package for code that belongs to one screen 
    ├── auth/
    ├── steps/
    ├── leaderboard/
    ├── map/
    ├── profile/
    ├── home/
    ├── gallery/
    └── slideshow/
```

---

## Adding a new screen to the sliding menu

Follow these 5 steps:

### 1. Create the feature package

Right-click `feature` → **New → Package** → type `newfeature.ui`

Create your Activity inside it:

```
feature/newfeature/ui/NewFeatureActivity.kt
```

If the feature needs a ViewModel or data classes, add:

```
feature/newfeature/viewmodel/NewFeatureViewModel.kt
feature/newfeature/model/SomeDataClass.kt
```

### 2. Create the layout XML

Create the layout file using the feature prefix naming convention:

```
res/layout/feature_newfeature_activity.xml
```

### 3. Register in AndroidManifest.xml

```xml
<activity
    android:name=".feature.newfeature.ui.NewFeatureActivity"
    android:label="New Feature" />
```

### 4. Add a menu item for the drawer

In `res/menu/activity_main_drawer.xml`, add:

```xml
<item
    android:id="@+id/nav_new_feature"
    android:icon="@drawable/ic_your_icon"
    android:title="New Feature" />
```

### 5. Handle the menu item in MainActivity

In `navigation/MainActivity.kt`, inside the `NavigationView.OnNavigationItemSelectedListener`, add:

```kotlin
R.id.nav_new_feature -> {
    startActivity(Intent(this, NewFeatureActivity::class.java))
}
```

---

## Adding a non-UI component (service, utility, data)

If the new code is **not a screen** but a shared component, place it in `core/`:

| Type                        | Where to put it |
|-----------------------------|-----------------|
| API client / repository     | `core/data/`    |
| Background service / worker | `core/service/` |
| Helper / utility class      | `core/util/`    |

---

## Naming conventions

| Type                 | Pattern                       | Example                             |
|----------------------|-------------------------------|-------------------------------------|
| Layout XML           | `feature_{name}_activity.xml` | `feature_leaderboard_activity.xml`  |
| List item XML        | `feature_{name}_item.xml`     | `feature_leaderboard_item.xml`      |
| Navigation layouts   | `nav_{name}.xml`              | `nav_main.xml`, `nav_app_bar.xml`   |