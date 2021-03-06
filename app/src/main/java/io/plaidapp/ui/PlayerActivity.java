/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.plaidapp.ui;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.SharedElementCallback;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.transition.TransitionManager;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.google.gson.GsonBuilder;

import java.text.NumberFormat;
import java.util.List;

import butterknife.Bind;
import butterknife.BindInt;
import butterknife.ButterKnife;
import butterknife.OnClick;
import io.plaidapp.R;
import io.plaidapp.data.PlaidItem;
import io.plaidapp.data.PlayerDataManager;
import io.plaidapp.data.api.AuthInterceptor;
import io.plaidapp.data.api.dribbble.DribbbleService;
import io.plaidapp.data.api.dribbble.model.User;
import io.plaidapp.data.pocket.PocketUtils;
import io.plaidapp.data.prefs.DribbblePrefs;
import io.plaidapp.ui.recyclerview.InfiniteScrollListener;
import io.plaidapp.ui.transitions.FabDialogMorphSetup;
import io.plaidapp.ui.widget.ElasticDragDismissFrameLayout;
import io.plaidapp.util.DribbbleUtils;
import io.plaidapp.util.ViewUtils;
import io.plaidapp.util.glide.CircleTransform;
import retrofit.Callback;
import retrofit.RestAdapter;
import retrofit.RetrofitError;
import retrofit.client.Response;
import retrofit.converter.GsonConverter;

/**
 * A screen displaying a player's details and their shots.
 */
public class PlayerActivity extends Activity {

    public static final String EXTRA_PLAYER = "EXTRA_PLAYER";
    public static final String EXTRA_PLAYER_NAME = "EXTRA_PLAYER_NAME";
    public static final String EXTRA_PLAYER_ID = "EXTRA_PLAYER_ID";
    public static final String EXTRA_PLAYER_USERNAME = "EXTRA_PLAYER_USERNAME";

    private User player;
    private CircleTransform circleTransform;
    private PlayerDataManager dataManager;
    private FeedAdapter adapter;
    private GridLayoutManager layoutManager;
    private ElasticDragDismissFrameLayout.SystemChromeFader chromeFader;
    private Boolean following;
    private int followerCount;

    @Bind(R.id.draggable_frame) ElasticDragDismissFrameLayout draggableFrame;
    @Bind(R.id.player_description) ViewGroup playerDescription;
    @Bind(R.id.avatar) ImageView avatar;
    @Bind(R.id.player_name) TextView playerName;
    @Bind(R.id.follow) Button follow;
    @Bind(R.id.player_bio) TextView bio;
    @Bind(R.id.shot_count) TextView shotCount;
    @Bind(R.id.followers_count) TextView followersCount;
    @Bind(R.id.likes_count) TextView likesCount;
    @Bind(R.id.loading) ProgressBar loading;
    @Bind(R.id.player_shots) RecyclerView shots;
    @BindInt(R.integer.num_columns) int columns;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dribbble_player);
        ButterKnife.bind(this);
        circleTransform = new CircleTransform(this);
        chromeFader = new ElasticDragDismissFrameLayout.SystemChromeFader(getWindow()) {
            @Override
            public void onDragDismissed() {
                finishAfterTransition();
            }
        };

        final Intent intent = getIntent();
        if (intent.hasExtra(EXTRA_PLAYER)) {
            player = intent.getParcelableExtra(EXTRA_PLAYER);
            bindPlayer();
            setupSharedEnter();
        } else if (intent.hasExtra(EXTRA_PLAYER_NAME)) {
            String name = intent.getStringExtra(EXTRA_PLAYER_NAME);
            playerName.setText(name);
            if (intent.hasExtra(EXTRA_PLAYER_ID)) {
                long userId = intent.getLongExtra(EXTRA_PLAYER_ID, 0l);
                loadPlayer(userId);
            } else if (intent.hasExtra(EXTRA_PLAYER_USERNAME)) {
                String username = intent.getStringExtra(EXTRA_PLAYER_USERNAME);
                loadPlayer(username);
            }
        } else if (intent.getData() != null) {
            // todo support url intents
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        draggableFrame.addListener(chromeFader);
    }

    @Override
    protected void onPause() {
        draggableFrame.removeListener(chromeFader);
        super.onPause();
    }

    private void bindPlayer() {
        final Resources res = getResources();
        final NumberFormat nf = NumberFormat.getInstance();

        Glide.with(this)
                .load(player.getHighQualityAvatarUrl())
                .placeholder(R.drawable.avatar_placeholder)
                .transform(circleTransform)
                .into(avatar);
        playerName.setText(player.name);
        if (!TextUtils.isEmpty(player.bio)) {
            DribbbleUtils.parseAndSetText(bio, player.bio);
        } else {
            bio.setVisibility(View.GONE);
        }

        shotCount.setText(res.getQuantityString(R.plurals.shots, player.shots_count,
                nf.format(player.shots_count)));
        followerCount = player.followers_count;
        setFollowerCount();
        likesCount.setText(res.getQuantityString(R.plurals.likes, player.likes_count,
                nf.format(player.likes_count)));

        // load the users shots
        dataManager = new PlayerDataManager(this, player) {
            @Override
            public void onDataLoaded(List<? extends PlaidItem> data) {
                if (data != null && data.size() > 0) {
                    if (adapter.getDataItemCount() == 0) {
                        loading.setVisibility(View.GONE);
                        ViewUtils.setPaddingTop(shots, playerDescription.getHeight());
                    }
                    adapter.addAndResort(data);
                }
            }
        };
        adapter = new FeedAdapter(this, dataManager, columns, PocketUtils.isPocketInstalled(this), false);
        shots.setAdapter(adapter);
        shots.setVisibility(View.VISIBLE);
        layoutManager = new GridLayoutManager(this, columns);
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return adapter.getItemColumnSpan(position);
            }
        });
        shots.setLayoutManager(layoutManager);
        shots.addOnScrollListener(new InfiniteScrollListener(layoutManager, dataManager) {
            @Override
            public void onLoadMore() {
                dataManager.loadMore();
            }
        });
        shots.setHasFixedSize(true);

        // forward on any clicks above the first item in the grid (i.e. in the paddingTop)
        // to 'pass through' to the view behind
        shots.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                final int firstVisible = layoutManager.findFirstVisibleItemPosition();
                if (firstVisible != 0) return false;

                final int firstTop = shots.findViewHolderForAdapterPosition(0).itemView.getTop();
                if (event.getY() < firstTop) {
                     return playerDescription.dispatchTouchEvent(event);
                }
                return false;
            }
        });

        // check if following
        if (dataManager.getDribbblePrefs().isLoggedIn()) {
            if (player.id == dataManager.getDribbblePrefs().getUserId()) {
                TransitionManager.beginDelayedTransition(playerDescription);
                follow.setVisibility(View.GONE);
                ViewUtils.setPaddingTop(shots, playerDescription.getHeight() - follow.getHeight()
                        - ((ViewGroup.MarginLayoutParams) follow.getLayoutParams()).bottomMargin);
            } else {
                dataManager.getDribbbleApi().following(player.id, new Callback<Void>() {
                    @Override
                    public void success(Void voyd, Response response) {
                        following = true;
                        TransitionManager.beginDelayedTransition(playerDescription);
                        follow.setText(R.string.following);
                        follow.setActivated(true);
                    }

                    @Override
                    public void failure(RetrofitError error) {
                        if (error.getResponse() != null && error.getResponse().getStatus() == 404) {
                            following = false;
                        }
                    }
                });
            }
        }

        if (player.shots_count > 0) {
            dataManager.loadMore(); // kick off initial load
        } else {
            // todo show empty text
        }
    }

    private void setupSharedEnter() {
        setEnterSharedElementCallback(new SharedElementCallback() {
            private float finalSize;
            private int finalPaddingStart;

            @Override
            public void onSharedElementStart(List<String> sharedElementNames, List<View>
                    sharedElements, List<View> sharedElementSnapshots) {
                finalSize = playerName.getTextSize();
                finalPaddingStart = playerName.getPaddingStart();
                playerName.setTextSize(14);
                ViewUtils.setPaddingStart(playerName, 0);
                forceSharedElementLayout();
            }

            @Override
            public void onSharedElementEnd(List<String> sharedElementNames, List<View>
                    sharedElements, List<View> sharedElementSnapshots) {
                playerName.setTextSize(TypedValue.COMPLEX_UNIT_PX, finalSize);
                ViewUtils.setPaddingStart(playerName, finalPaddingStart);
                forceSharedElementLayout();
            }

            private void forceSharedElementLayout() {
                playerName.measure(
                        View.MeasureSpec.makeMeasureSpec(playerName.getWidth(),
                                View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(playerName.getHeight(),
                                View.MeasureSpec.EXACTLY));
                playerName.layout(playerName.getLeft(),
                        playerName.getTop(),
                        playerName.getRight(),
                        playerName.getBottom());
            }
        });
    }

    private void loadPlayer(long userId) {
        getDribbbleApi().getUser(userId, new Callback<User>() {
            @Override
            public void success(User user, Response response) {
                player = user;
                bindPlayer();
            }

            @Override public void failure(RetrofitError error) { }
        });
    }

    private void loadPlayer(String username) {
        getDribbbleApi().getUser(username, new Callback<User>() {
            @Override
            public void success(User user, Response response) {
                player = user;
                bindPlayer();
            }

            @Override public void failure(RetrofitError error) { }
        });
    }

    private DribbbleService getDribbbleApi() {
        return new RestAdapter.Builder()
                .setEndpoint(DribbbleService.ENDPOINT)
                .setConverter(new GsonConverter(new GsonBuilder()
                        .setDateFormat(DribbbleService.DATE_FORMAT)
                        .create()))
                .setRequestInterceptor(new AuthInterceptor(DribbblePrefs.get(this)
                        .getAccessToken()))
                .build()
                .create((DribbbleService.class));
    }

    private void setFollowerCount() {
        followersCount.setText(getResources().getQuantityString(R.plurals.follower_count,
                followerCount, NumberFormat.getInstance().format(followerCount)));
    }

    @OnClick(R.id.follow)
    /* package */ void follow() {
        if (dataManager.getDribbblePrefs().isLoggedIn()) {
            if (following != null && following) {
                dataManager.getDribbbleApi().unfollow(player.id, new Callback<Void>() {
                    @Override public void success(Void voyd, Response response) { }

                    @Override public void failure(RetrofitError error) { }
                });
                following = false;
                TransitionManager.beginDelayedTransition(playerDescription);
                follow.setText(R.string.follow);
                follow.setActivated(false);
                followerCount--;
                setFollowerCount();
            } else {
                dataManager.getDribbbleApi().follow(player.id, "", new Callback<Void>() {
                    @Override public void success(Void voyd, Response response) { }

                    @Override public void failure(RetrofitError error) { }
                });
                following = true;
                TransitionManager.beginDelayedTransition(playerDescription);
                follow.setText(R.string.following);
                follow.setActivated(true);
                followerCount++;
                setFollowerCount();
            }
        } else {
            Intent login = new Intent(this, DribbbleLogin.class);
            login.putExtra(FabDialogMorphSetup.EXTRA_SHARED_ELEMENT_START_COLOR,
                    ContextCompat.getColor(this, R.color.dribbble));
            login.putExtra(FabDialogMorphSetup.EXTRA_SHARED_ELEMENT_START_CORNER_RADIUS,
                    getResources().getDimensionPixelSize(R.dimen.dialog_corners));
            ActivityOptions options = ActivityOptions.makeSceneTransitionAnimation
                    (this, follow, getString(R.string.transition_dribbble_login));
            startActivity(login, options.toBundle());
        }
    }

}
