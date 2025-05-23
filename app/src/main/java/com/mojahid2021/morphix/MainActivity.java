package com.mojahid2021.morphix;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.ar.core.AugmentedImage;
import com.google.ar.core.AugmentedImageDatabase;
import com.google.ar.core.Config;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.sceneform.ux.ArFragment;

import java.io.File;
import java.util.Collection;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private ArFragment arFragment;  // Correct ArFragment from Sceneform
    private ImageCapture imageCapture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize ArFragment programmatically
        arFragment = new ArFragment();

        // Add ArFragment dynamically to the container
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.container, arFragment)  // Use the container's ID
                    .commit();
        }

        // Set up CameraX to capture image
        startCamera();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Ensure AR fragment and its ARSceneView are fully initialized
        if (arFragment != null && arFragment.getArSceneView() != null) {
            arFragment.getArSceneView().getScene().addOnUpdateListener(frameTime -> {
                try {
                    Session arSession = arFragment.getArSceneView().getSession();
                    if (arSession != null) {
                        // Handle AR session updates here
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error during AR session update: ", e);
                }
            });
        } else {
            Log.e(TAG, "ArFragment or ArSceneView is not initialized properly.");
        }
    }

    private void startCamera() {
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = ProcessCameraProvider.getInstance(this).get();
                androidx.camera.core.Preview preview = new androidx.camera.core.Preview.Builder().build();
                imageCapture = new ImageCapture.Builder().build();

                androidx.camera.core.CameraSelector cameraSelector = androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();

                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);

                // Set up PreviewView for CameraX
                androidx.camera.view.PreviewView previewView = findViewById(R.id.previewView);
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                // Capture the image when the user presses the capture button
                Button captureButton = findViewById(R.id.capture_button);
                captureButton.setOnClickListener(v -> captureImage());

            } catch (Exception e) {
                Log.e(TAG, "Camera setup failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void captureImage() {
        File file = new File(getExternalFilesDir(null), "captured_image.jpg");

        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(file).build();
        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(ImageCapture.OutputFileResults outputFileResults) {
                String savedUri = outputFileResults.getSavedUri().toString();
                Log.d(TAG, "Image saved: " + savedUri);
                processCapturedImage(file);
            }

            @Override
            public void onError(ImageCaptureException exception) {
                Log.e(TAG, "Image capture failed: " + exception.getMessage());
            }
        });
    }

    private void processCapturedImage(File imageFile) {
        try {
            Bitmap bitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath());
            AugmentedImageDatabase imageDatabase = new AugmentedImageDatabase(arFragment.getArSceneView().getSession());

            // Add image to the database with correct dimensions (image width in meters)
            float imageWidthInMeters = 0.1f;  // Example width, modify as necessary
            imageDatabase.addImage("captured_image", bitmap, imageWidthInMeters);

            // Set up ARCore configuration
            Config config = new Config(arFragment.getArSceneView().getSession());
            config.setAugmentedImageDatabase(imageDatabase);
            arFragment.getArSceneView().getSession().configure(config);

            Toast.makeText(this, "Image captured and processed!", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Error processing captured image", e);
        }
    }

    private void updateAugmentedImages(Collection<AugmentedImage> updatedImages) {
        for (AugmentedImage img : updatedImages) {
            if (img.getTrackingState() == TrackingState.TRACKING) {
                switch (img.getTrackingMethod()) {
                    case FULL_TRACKING:
                        // Augment the image with 3D content
                        Log.d(TAG, "Augmented image detected: " + img.getName());
                        break;
                    case LAST_KNOWN_POSE:
                        // Handle lost tracking
                        break;
                    case NOT_TRACKING:
                        // Handle when image is no longer tracked
                        break;
                }
            }
        }
    }
}
