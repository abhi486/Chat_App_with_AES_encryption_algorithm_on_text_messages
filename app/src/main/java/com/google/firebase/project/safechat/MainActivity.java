package com.google.firebase.project.safechat;

import android.annotation.TargetApi;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
//import android.support.v7.app.AppCompatActivity;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.firebase.ui.auth.AuthUI;
import com.google.android.gms.auth.api.Auth;
import com.google.android.gms.tasks.Continuation;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    public static final String ANONYMOUS = "anonymous";
    public static final int DEFAULT_MSG_LENGTH_LIMIT = 1000;
    public static final int RC_SIGN_IN = 1;
    public static final int RC_PHOTO_PICKER = 5;
    private static final String passwordText = "ACVALHALLA";

    private ListView mMessageListView;
    private MessageAdapter mMessageAdapter;
    private ProgressBar mProgressBar;
    private ImageButton mPhotoPickerButton;
    private EditText mMessageEditText;
    private Button mSendButton;

    private String mUsername;
    private FirebaseDatabase mFireBaseDatabase;
    private DatabaseReference mDataBaseReference;
    private ChildEventListener mChildEventListener;

    private FirebaseAuth mFireBaseAuth;
    private FirebaseAuth.AuthStateListener mAuthStateListener;

    private FirebaseStorage mFireBaseStorage;
    private StorageReference mStorageReference;

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mUsername = ANONYMOUS;

        mFireBaseDatabase = FirebaseDatabase.getInstance();
        mFireBaseAuth = FirebaseAuth.getInstance();
        mFireBaseStorage = FirebaseStorage.getInstance();

        mDataBaseReference = mFireBaseDatabase.getReference().child("messages");
        mStorageReference = mFireBaseStorage.getReference().child("chat_photos");

        // Initialize references to views
        mProgressBar = findViewById(R.id.progressBar);
        mMessageListView = findViewById(R.id.messageListView);
        mPhotoPickerButton = findViewById(R.id.photoPickerButton);
        mMessageEditText = findViewById(R.id.messageEditText);
        mSendButton = findViewById(R.id.sendButton);

        // Initialize message ListView and its adapter
        List<FriendlyMessage> friendlyMessages = new ArrayList<>();
        mMessageAdapter = new MessageAdapter(this, R.layout.item_message, friendlyMessages);
        mMessageListView.setAdapter(mMessageAdapter);

        // Initialize progress bar
        mProgressBar.setVisibility(ProgressBar.INVISIBLE);

        // ImagePickerButton shows an image picker to upload a image for a message
        mPhotoPickerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // TODO: Fire an intent to show an image picker
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("image/jpeg");
                intent.putExtra(Intent.EXTRA_LOCAL_ONLY,true);
                startActivityForResult(intent,RC_PHOTO_PICKER);
            }
        });

        // Enable Send button when there's text to send
        mMessageEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                if (charSequence.toString().trim().length() > 0) {
                    mSendButton.setEnabled(true);
                } else {
                    mSendButton.setEnabled(false);
                }
            }

            @Override
            public void afterTextChanged(Editable editable) {
            }
        });
        mMessageEditText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(DEFAULT_MSG_LENGTH_LIMIT)});

        // Send button sends a message and clears the EditText
        mSendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // TODO: Send messages on click
                String encryptedText = "";
                try {
                    encryptedText = encrypt(mMessageEditText.getText().toString(),passwordText);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                FriendlyMessage message = new FriendlyMessage(encryptedText,mUsername,null);
                mDataBaseReference.push().setValue(message);
                // Clear input box
                mMessageEditText.setText("");
            }
        });
        mAuthStateListener = new FirebaseAuth.AuthStateListener() {
            @Override
            public void onAuthStateChanged(@NonNull FirebaseAuth firebaseAuth) {
                FirebaseUser user = firebaseAuth.getCurrentUser();
                if (user != null){
                    //User is signed in
                    onSignedIn(user.getDisplayName());
                }
                else{
                    //User is signed out
                    onSignedOut();
                    startActivityForResult(
                            AuthUI.getInstance()
                                    .createSignInIntentBuilder()
                                    .setIsSmartLockEnabled(false)
                                    .setAvailableProviders(Arrays.asList(
                                            new AuthUI.IdpConfig.GoogleBuilder().build(),
                                            new AuthUI.IdpConfig.EmailBuilder().build()))
                                    .build(),
                            RC_SIGN_IN);
                }
            }
        };
    }

    private void onSignedIn(String username) {
        mUsername = username;
        attachToDataBase();
    }

    private void attachToDataBase() {
        if (mChildEventListener == null) {
            mChildEventListener = new ChildEventListener() {
                @Override
                public void onChildAdded(@NonNull DataSnapshot snapshot, @Nullable String previousChildName) {
                    FriendlyMessage friendlyMessage = snapshot.getValue(FriendlyMessage.class);
                    String to_decrypt = friendlyMessage.getText();
                    String decrypted = "";
                    try {
                        decrypted = decrypt(to_decrypt,passwordText);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    friendlyMessage.setText(decrypted.toString());
                    mMessageAdapter.add(friendlyMessage);
                }

                @Override
                public void onChildChanged(@NonNull DataSnapshot snapshot, @Nullable String previousChildName) {

                }

                @Override
                public void onChildRemoved(@NonNull DataSnapshot snapshot) {

                }

                @Override
                public void onChildMoved(@NonNull DataSnapshot snapshot, @Nullable String previousChildName) {

                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {

                }
            };
            mDataBaseReference.addChildEventListener(mChildEventListener);
        }
    }

    private void onSignedOut(){
        mUsername = ANONYMOUS;
        mMessageAdapter.clear();
        detachFromDataBase();
    }

    private void detachFromDataBase(){
        if (mChildEventListener != null) {
            mDataBaseReference.removeEventListener(mChildEventListener);
        }
    }
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.sign_out_menu) {
            AuthUI.getInstance().signOut(MainActivity.this);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_SIGN_IN){
            if (resultCode == RESULT_OK){
                Toast.makeText(MainActivity.this,"Welcome! You're signed in",Toast.LENGTH_SHORT).show();
            }
            else if (resultCode == RESULT_CANCELED){
                //Toast.makeText(MainActivity.this,"Sign in cancelled",Toast.LENGTH_SHORT).show();
                finish();
            }
        }
        else if (requestCode == RC_PHOTO_PICKER && resultCode == RESULT_OK){
            Uri uri = data.getData();
            final StorageReference photoReference = mStorageReference.child(uri.getLastPathSegment());
            UploadTask uploadTask = photoReference.putFile(uri);
            uploadTask.addOnFailureListener(new OnFailureListener() {
                @Override
                public void onFailure(@NonNull Exception e) {
                    Toast.makeText(MainActivity.this,"Something went wrong!",Toast.LENGTH_SHORT).show();
                }
            });
            Task<Uri> urlTask = uploadTask.continueWithTask(new Continuation<UploadTask.TaskSnapshot, Task<Uri>>() {
                @Override
                public Task<Uri> then(@NonNull Task<UploadTask.TaskSnapshot> task) throws Exception {
                    if (!task.isSuccessful()){
                        throw task.getException();
                    }
                    return photoReference.getDownloadUrl();
                }
            }).addOnCompleteListener(new OnCompleteListener<Uri>() {
                @Override
                public void onComplete(@NonNull Task<Uri> task) {
                    if (task.isSuccessful()){
                        Uri downloadUrl = task.getResult();
                        Log.d(TAG, "onComplete: " + downloadUrl);
                        FriendlyMessage message = new FriendlyMessage(null,mUsername,downloadUrl.toString());
                        mDataBaseReference.push().setValue(message);
                    }
                    else{
                        Toast.makeText(MainActivity.this,"Something went wrong!",Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mFireBaseAuth.addAuthStateListener(mAuthStateListener);
        detachFromDataBase();
        mMessageAdapter.clear();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mFireBaseAuth.removeAuthStateListener(mAuthStateListener);
    }

    private String encrypt(String data, String password_text) throws Exception {
        SecretKeySpec key = generateKey(password_text);
        //Log.d("NIKHIL", "encrypt key:" + key.toString());
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");//creating an object
        c.init(Cipher.ENCRYPT_MODE, key);//initialisation
        byte[] encVal = c.doFinal(data.getBytes("UTF-8"));
        //Encrypts or decrypts data in a single-part operation, or finishes a multiple-part operation
        String encryptedvalue = Base64.encodeToString(encVal, Base64.DEFAULT);
        //Base64-encode the given data and return a newly allocated String with the result.
        //It's basically a way of encoding arbitrary binary data in ASCII text. It takes 4 characters per 3 bytes of data,
        // plus potentially a bit of padding at the end.
        //Essentially each 6 bits of the input is encoded in a 64-character alphabet.
        //The "standard" alphabet uses A-Z, a-z, 0-9 and + and /, with = as a padding character. There are URL-safe variants.
        return encryptedvalue;

    }

    private String decrypt(String data, String password_text) throws Exception {
        SecretKeySpec key = generateKey(password_text);
        // Log.d("NIKHIL", "encrypt key:" + key.toString());
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, key);
        byte[] decodedvalue = Base64.decode(data, Base64.DEFAULT);//pehle vo base64 me encoded tha, to decode to karna padega na
        byte[] decvalue = c.doFinal(decodedvalue);//final decoding operation
        String decryptedvalue = new String(decvalue, "UTF-8");//converting bytes into string
        return decryptedvalue;
    }

    private SecretKeySpec generateKey(String password) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");//for using hash function SHA-256
        byte[] bytes = password.getBytes("UTF-8");
        digest.update(bytes, 0, bytes.length);//process kr bytes array ko
        byte[] key = digest.digest();////Completes the hash computation by performing final operations such as padding.
        SecretKeySpec secretKeySpec = new SecretKeySpec(key, "AES");
        return secretKeySpec;
    }
}
