package com.mojahid2021.morphix;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.AugmentedImage;
import com.google.ar.core.AugmentedImageDatabase;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.FrameTime;
import com.google.ar.sceneform.Scene;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.MaterialFactory;
import com.google.ar.sceneform.rendering.ModelRenderable;
import com.google.ar.sceneform.rendering.ShapeFactory;
import com.google.ar.sceneform.ux.ArFragment;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

public class MainActivity extends AppCompatActivity implements Scene.OnUpdateListener {

    private static final String TAG = "AugmentedImageApp";
    private static final double MIN_OPENGL_VERSION = 3.0;
    private static final int CAMERA_PERMISSION_CODE = 0;

    private ArFragment arFragment;
    private Session arSession;
    private Config config;
    private AugmentedImageDatabase augmentedImageDatabase;
    private Bitmap scannedImageBitmap;
    private boolean isScanning = true; // True when waiting for user to "scan" an image
    private Button scanButton;
    private TextView messageTextView;

    // To store the rendered object on the image, so we don't re-render it
    private ModelRenderable cubeRenderable;
    private AnchorNode augmentedImageAnchorNode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Check if the device supports ARCore and has the necessary OpenGL ES version
        if (!checkIsSupportedDeviceOrFinish()) {
            return;
        }

        // Request camera permission if not already granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
        } else {
            // If permission is already granted, proceed with setting up the AR fragment
            setupArFragment();
        }

        // Build the cube renderable once
        buildCubeRenderable();
    }

    /**
     * Sets up the ArFragment and its listeners once camera permission is granted.
     */
    private void setupArFragment() {
        setContentView(R.layout.activity_main); // Set the layout containing the ArFragment
        arFragment = (ArFragment) getSupportFragmentManager().findFragmentById(R.id.ar_fragment);

        // Get references to UI elements
        scanButton = findViewById(R.id.scan_button);
        messageTextView = findViewById(R.id.message_text_view);

        if (arFragment != null) {
            // Add an update listener to the AR scene to process frames
            arFragment.getArSceneView().getScene().addOnUpdateListener(this);

            // Hide the default plane discovery controller as we are focusing on image tracking
            // This prevents the white dots from appearing for plane detection.
            arFragment.getPlaneDiscoveryController().hide();
            arFragment.getPlaneDiscoveryController().setInstructionView(null);
        }
    }

    /**
     * Handles the result of the camera permission request.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, proceed with setup
                setupArFragment();
            } else {
                // Permission denied, inform user and exit
                Toast.makeText(this, "Camera permission is required to use AR.", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    /**
     * Initializes the ARCore Session and configures it.
     * This method is called in onResume or when a new database is created.
     */
    private void initializeArSession() throws UnavailableArcoreNotInstalledException, UnavailableUserDeclinedInstallationException {
        if (arSession == null) {
            // Create a new ARCore session
            try {
                arSession = new Session(/*context=*/this);
            } catch (UnavailableApkTooOldException e) {
                throw new RuntimeException(e);
            } catch (UnavailableSdkTooOldException e) {
                throw new RuntimeException(e);
            } catch (UnavailableDeviceNotCompatibleException e) {
                throw new RuntimeException(e);
            }
        }
        if (config == null) {
            // Create a new ARCore configuration
            config = new Config(arSession);
            // Enable auto focus for better image detection
            config.setFocusMode(Config.FocusMode.AUTO);
            // **Set the update mode for Sceneform**
            // Sceneform requires LATEST_CAMERA_IMAGE for rendering consistency.
            config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
        }
        // If an augmented image database exists, set it in the config
        if (augmentedImageDatabase != null) {
            config.setAugmentedImageDatabase(augmentedImageDatabase);
        }
        // Apply the configuration to the session
        arSession.configure(config);
        // Set up the ARSceneView with the new session
        arFragment.getArSceneView().setupSession(arSession);
    }

    /**
     * This method is called when the "Scan Image" button is clicked.
     * In a real app, this would trigger actual camera image capture.
     * For this example, it loads a static image from assets.
     */
    public void onScanButtonClicked(View view) {
        if (isScanning) {
            messageTextView.setText("Scanning...");
            simulateImageCapture();
        } else {
            Toast.makeText(this, "Image already scanned and tracking.", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Simulates capturing an image from the camera.
     * In a real application, you would use CameraX or Camera2 API here to get a Bitmap
     * from the live camera feed.
     */
    private void simulateImageCapture() {
        try {
            // Load a sample bitmap from assets. You MUST place 'your_image.jpg' in app/src/main/assets/
            InputStream inputStream = getAssets().open("your_image.jpg");
            scannedImageBitmap = BitmapFactory.decodeStream(inputStream);
            inputStream.close();

            if (scannedImageBitmap != null) {
                // Create and configure the database on an AsyncTask to avoid blocking the UI thread
                new CreateDatabaseTask().execute(scannedImageBitmap);
            } else {
                Log.e(TAG, "Failed to decode the sample image.");
                Toast.makeText(this, "Failed to load image for scanning.", Toast.LENGTH_SHORT).show();
                messageTextView.setText("Failed to load image.");
            }
        } catch (IOException e) {
            Log.e(TAG, "Error loading sample image: " + e.getMessage());
            Toast.makeText(this, "Error loading image from assets. Make sure 'your_image.jpg' is in assets folder.", Toast.LENGTH_LONG).show();
            messageTextView.setText("Error loading image from assets.");
        }
    }

    /**
     * Creates an AugmentedImageDatabase at runtime with the provided bitmap
     * and configures the ARCore session. This is done on a background thread
     * to avoid blocking the main UI thread.
     */
    private class CreateDatabaseTask extends AsyncTask<Bitmap, Void, AugmentedImageDatabase> {
        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            messageTextView.setText("Processing image...");
            scanButton.setEnabled(false); // Disable button during processing
        }

        @Override
        protected AugmentedImageDatabase doInBackground(Bitmap... bitmaps) {
            Bitmap bitmap = bitmaps[0];
            AugmentedImageDatabase database = null;
            try {
                // Ensure arSession is initialized before creating the database
                if (arSession == null) {
                    // This scenario should ideally be handled by initializeArSession in onResume,
                    // but adding a fallback for robustness.
                    arSession = new Session(MainActivity.this);
                }
                database = new AugmentedImageDatabase(arSession);
                // Provide an estimated physical width of the image in meters.
                // This improves detection performance. If unknown, ARCore will estimate.
                float imageWidthInMeters = 0.2f; // Example: 20 cm wide
                database.addImage("scanned_image", bitmap, imageWidthInMeters);
            } catch (Exception e) {
                Log.e(TAG, "Error creating AugmentedImageDatabase: " + e.getMessage());
                // Handle the error appropriately, e.g., show a Toast
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error creating image database: " + e.getMessage(), Toast.LENGTH_LONG).show());
                return null;
            }
            return database;
        }

        @Override
        protected void onPostExecute(AugmentedImageDatabase database) {
            if (database != null) {
                augmentedImageDatabase = database;
                // Reconfigure the session with the new database
                if (config == null) {
                    config = new Config(arSession);
                    config.setFocusMode(Config.FocusMode.AUTO);
                    config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
                }
                config.setAugmentedImageDatabase(augmentedImageDatabase);
                arSession.configure(config);

                isScanning = false; // Transition from scanning mode to tracking mode
                scanButton.setText("Image Scanned");
                messageTextView.setText("Image scanned. Now tracking...");
                Toast.makeText(MainActivity.this, "Image scanned. Now tracking...", Toast.LENGTH_LONG).show();
            } else {
                messageTextView.setText("Image processing failed.");
                scanButton.setEnabled(true); // Re-enable button on failure
            }
        }
    }

    /**
     * Builds a simple cube renderable. In a real app, you might load a more complex 3D model.
     */
    private void buildCubeRenderable() {
        MaterialFactory.makeOpaqueWithColor(this, new com.google.ar.sceneform.rendering.Color(android.graphics.Color.BLUE))
                .thenAccept(
                        material -> {
                            cubeRenderable =
                                    ShapeFactory.makeCube(new Vector3(0.1f, 0.1f, 0.1f),
                                            new Vector3(0.0f, 0.05f, 0.0f), material);
                        })
                .exceptionally(
                        throwable -> {
                            Log.e(TAG, "Unable to load Renderable.", throwable);
                            Toast.makeText(this, "Failed to load 3D model.", Toast.LENGTH_LONG).show();
                            return null;
                        });
    }

    /**
     * This method is called every frame update by Sceneform.
     * It's where you check for detected Augmented Images and augment them.
     *
     * @param frameTime Provides information about the current frame.
     */
    @Override
    public void onUpdate(FrameTime frameTime) {
        if (arSession == null) {
            return;
        }

        // Get the current AR frame from the SceneView
        Frame frame = arFragment.getArSceneView().getArFrame();
        if (frame == null) {
            return;
        }

        // Get all AugmentedImage trackables that have been updated this frame
        Collection<AugmentedImage> updatedAugmentedImages =
                frame.getUpdatedTrackables(AugmentedImage.class);

        for (AugmentedImage augmentedImage : updatedAugmentedImages) {
            // Check if the tracking state is TRACKING and if it's our "scanned_image"
            if (augmentedImage.getTrackingState() == TrackingState.TRACKING
                    && augmentedImage.getName().equals("scanned_image")) {

                if (augmentedImageAnchorNode == null) { // Only augment once
                    Log.d(TAG, "Detected and tracking the scanned image!");
                    messageTextView.setText("Image detected and tracking!");

                    // Create an anchor at the center of the detected image
                    Anchor anchor = augmentedImage.createAnchor(augmentedImage.getCenterPose());

                    // Create an AnchorNode and set its parent to the scene
                    augmentedImageAnchorNode = new AnchorNode(anchor);
                    augmentedImageAnchorNode.setParent(arFragment.getArSceneView().getScene());

                    // Set the renderable (the cube) to the AnchorNode
                    if (cubeRenderable != null) {
                        augmentedImageAnchorNode.setRenderable(cubeRenderable);
                        // You can also add other nodes or models here
                        // For example, to make the cube appear slightly above the image:
                        // augmentedImageAnchorNode.setLocalPosition(new Vector3(0, 0.05f, 0)); // Adjust Y for height
                    } else {
                        Log.e(TAG, "Cube renderable is null, cannot augment image.");
                        messageTextView.setText("Error: 3D model not ready.");
                    }
                    // Once detected and augmented, you might want to stop the initial "scanning" phase
                    // and potentially hide the scan button if it's a one-time scan.
                    // isScanning = false; // If you want to stop re-detecting
                    // scanButton.setVisibility(View.GONE); // If you want to hide the button
                }
            } else if (augmentedImage.getTrackingState() == TrackingState.PAUSED && isScanning) {
                // The image has been recognized but not yet fully tracked (e.g., ARCore is estimating size)
                Log.d(TAG, "Image recognized, but not yet fully tracked. Looking for more data...");
                messageTextView.setText("Image recognized, refining tracking...");
            } else if (augmentedImage.getTrackingState() == TrackingState.STOPPED) {
                // The image is no longer being tracked
                Log.d(TAG, "Image tracking stopped.");
                messageTextView.setText("Image tracking lost. Move camera to re-detect.");
                if (augmentedImageAnchorNode != null) {
                    augmentedImageAnchorNode.setRenderable(null); // Remove rendered object
                    augmentedImageAnchorNode = null; // Clear the node
                }
            }
        }
    }

    /**
     * Checks if the device supports ARCore and meets OpenGL ES version requirements.
     * If not, it displays a message and finishes the activity.
     */
    private boolean checkIsSupportedDeviceOrFinish() {
        // Check Android version compatibility
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "ARCore requires Android N (API 24) or later.");
            Toast.makeText(this, "ARCore requires Android N or later.", Toast.LENGTH_LONG).show();
            finish();
            return false;
        }

        // Check OpenGL ES version
        String openGlVersionString =
                ((ActivityManager) getSystemService(Context.ACTIVITY_SERVICE))
                        .getDeviceConfigurationInfo()
                        .getGlEsVersion();
        if (Double.parseDouble(openGlVersionString) < MIN_OPENGL_VERSION) {
            Log.e(TAG, "ARCore requires OpenGL ES 3.0 or later.");
            Toast.makeText(this, "ARCore requires OpenGL ES 3.0 or later.", Toast.LENGTH_LONG)
                    .show();
            finish();
            return false;
        }

        // Check ARCore availability
        ArCoreApk.Availability availability = ArCoreApk.getInstance().checkAvailability(this);
        if (availability.isTransient()) {
            // ARCore is still checking compatibility. Re-query after a short delay.
            new android.os.Handler().postDelayed(() -> checkIsSupportedDeviceOrFinish(), 200);
            return false; // Return false to indicate not ready yet
        }
        if (!availability.isSupported()) {
            // ARCore is not supported on this device.
            Log.e(TAG, "ARCore is not supported on this device.");
            Toast.makeText(this, "ARCore is not supported on this device.", Toast.LENGTH_LONG).show();
            finish();
            return false;
        }
        return true;
    }

    /**
     * Resumes the ARCore session when the activity resumes.
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (arSession == null) {
            // Attempt to initialize AR session if it's null (e.g., after permission grant)
            try {
                initializeArSession();
            } catch (UnavailableArcoreNotInstalledException | UnavailableUserDeclinedInstallationException e) {
                Toast.makeText(this, "Failed to create AR session: " + e.getMessage(), Toast.LENGTH_LONG).show();
                Log.e(TAG, "Failed to create AR session", e);
            }
        }
        if (arSession != null) {
            try {
                arSession.resume();
            } catch (CameraNotAvailableException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Pauses the ARCore session when the activity pauses.
     */
    @Override
    protected void onPause() {
        super.onPause();
        if (arSession != null) {
            arSession.pause();
        }
    }
}