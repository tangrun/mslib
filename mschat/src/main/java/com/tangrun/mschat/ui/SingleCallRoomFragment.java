package com.tangrun.mschat.ui;

import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.tangrun.mschat.MSManager;
import com.tangrun.mschat.R;
import com.tangrun.mschat.databinding.MsFragmentSingleCallBinding;
import com.tangrun.mschat.databinding.MsLayoutActionBinding;
import com.tangrun.mschat.model.BuddyModel;
import com.tangrun.mschat.model.IBuddyModelObserver;
import com.tangrun.mschat.model.UIRoomStore;
import com.tangrun.mslib.enums.*;
import com.tangrun.mslib.lv.ChangedMutableLiveData;
import com.tangrun.mslib.utils.ArchTaskExecutor;
import org.jetbrains.annotations.NotNull;
import org.webrtc.VideoTrack;

import java.util.Arrays;

/**
 * @author RainTang
 * @description:
 * @date :2022/2/14 9:36
 */
public class SingleCallRoomFragment extends Fragment {
    private static final String TAG = "MS_UI_SingleCall";

    public static SingleCallRoomFragment newInstance() {

        Bundle args = new Bundle();

        SingleCallRoomFragment fragment = new SingleCallRoomFragment();
        fragment.setArguments(args);
        return fragment;
    }

    MsFragmentSingleCallBinding binding;
    ChangedMutableLiveData<BuddyModel> target = new ChangedMutableLiveData<>();
    UIRoomStore uiRoomStore;
    ChangedMutableLiveData<Boolean> mimeShowFullRender = new ChangedMutableLiveData<>(false);
    Boolean mimeShowFullRenderActual = null;

    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = MsFragmentSingleCallBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull @NotNull View view, @Nullable @org.jetbrains.annotations.Nullable Bundle savedInstanceState) {
        uiRoomStore = MSManager.getCurrent();

        uiRoomStore.mine.observe(this, buddyModel -> {
            Log.d(TAG, "mime get : " + buddyModel);
            buddyModel.videoTrack.observe(this, videoTrack -> {
                Log.d(TAG, "onMineVideoTrackChanged: " + videoTrack);
                resetRender();
            });
        });
        mimeShowFullRender.observe(this, aBoolean -> {
            resetRender();
        });
        uiRoomStore.cameraFacingState.observe(this, cameraFacingState -> {
            if (cameraFacingState == CameraFacingState.inProgress) return;
            resetRender();
        });
        target.observe(this, buddyModel -> {
            if (buddyModel == null) return;
            // 仅在初始化时调用
            binding.msTvUserName.setText(buddyModel.buddy.getDisplayName());
            Glide.with(binding.msIvUserAvatar).load(buddyModel.buddy.getAvatar())
                    .apply(new RequestOptions()
                            .error(R.drawable.ms_default_avatar)
                            .placeholder(R.drawable.ms_default_avatar))
                    .into(binding.msIvUserAvatar);
            buddyModel.videoTrack.observe(SingleCallRoomFragment.this, videoTrack -> {
                Log.d(TAG, "onTargetVideoTrackChanged: " + videoTrack);
                resetRender();
            });
            buddyModel.state.observe(SingleCallRoomFragment.this, unused -> {
                setTipText();
            });
        });

        // 重新打开界面时 人已经进入过就不会重新回调client的接口回调 所以buddyObservable就失效了 需要从list里找
        for (BuddyModel buddyModel : uiRoomStore.buddyModels) {
            if (!buddyModel.buddy.isProducer() && target.getValue() == null) {
                target.applySet(buddyModel);
                break;
            }
        }
        uiRoomStore.buddyObservable.registerObserver(new IBuddyModelObserver() {
            @Override
            public void onBuddyAdd(int position, BuddyModel buddyModel) {
                if (!buddyModel.buddy.isProducer() && target.getValue() == null) {
                    target.applySet(buddyModel);
                }
            }

            @Override
            public void onBuddyRemove(int position, BuddyModel buddyModel) {
                if (buddyModel == target.getValue()) {
                    target.applySet(null);
                }
            }
        });

        binding.msVRendererFull.init(this);
        binding.msVRendererWindow.init(this);
        binding.msVRendererWindow.setZOrderOnTop(true);
        binding.msVRendererFull.setZOrderOnTop(false);
        uiRoomStore.sendTransportState.observe(this, state -> {
            if (state == TransportState.disposed) {
                if (mimeShowFullRenderActual == Boolean.TRUE) {
                    binding.msVRendererFull.bind(this, true, null);
                } else if (mimeShowFullRenderActual == Boolean.FALSE) {
                    binding.msVRendererWindow.bind(this, true, null);
                }
            }
        });
        uiRoomStore.recvTransportState.observe(this, state -> {
            if (state == TransportState.disposed) {
                if (mimeShowFullRenderActual == Boolean.TRUE) {
                    binding.msVRendererWindow.bind(this, true, null);
                } else if (mimeShowFullRenderActual == Boolean.FALSE) {
                    binding.msVRendererFull.bind(this, true, null);
                }
            }
        });

        binding.msVRendererWindow.setOnClickListener(v -> {
            mimeShowFullRender.applySet(!mimeShowFullRender.getValue());
        });
        binding.msIvMinimize.setOnClickListener(v -> {
            uiRoomStore.onMinimize(getActivity());
        });
        binding.msRoot.setOnClickListener(v -> {
            if (uiRoomStore.callingActual.getValue() != Boolean.TRUE || uiRoomStore.audioOnly) return;
            showUI(!showUI);
        });
        // 通话时间
        uiRoomStore.callTime.observe(this, s -> {
            if (s == null) binding.msTvTime.setVisibility(View.GONE);
            binding.msTvTime.setText(s);
        });
        uiRoomStore.callingActual.observe(this, aBoolean -> {
            // 状态提示 开使通话用状态不能判断出来 所以新加calling
            if (aBoolean) setTipText();
            resetRender();
        });
        uiRoomStore.localState.observeForever(localState -> {
            Log.d(TAG, "onViewCreated: " + localState);
            // 状态提示
            setTipText();
            showUI(showUI);

            // action
            hideAllAction();
            if (localState.second == ConversationState.Invited) {
                // 接听/挂断
                uiRoomStore.Action_HangupAction.bindView(binding.llActionBottomLeft);
                uiRoomStore.Action_JoinAction.bindView(binding.llActionBottomRight);
            } else if (localState.second == ConversationState.Joined
                    || (localState.second == ConversationState.New && localState.first == LocalConnectState.JOINED)) {
                if (uiRoomStore.audioOnly) {
                    // 麦克风/挂断/扬声器
                    uiRoomStore.Action_SpeakerOn.bindView(binding.llActionBottomLeft);
                    uiRoomStore.Action_HangupAction.bindView(binding.llActionBottomCenter);
                    uiRoomStore.Action_MicrophoneDisabled.bindView(binding.llActionBottomRight);
                } else {
                    // 麦克风/摄像头/切换摄像头 挂断
                    uiRoomStore.Action_SpeakerOn.bindView(binding.llActionBottomLeft);
                    uiRoomStore.Action_HangupAction.bindView(binding.llActionBottomCenter);
                    uiRoomStore.Action_CameraNotFacing.bindView(binding.llActionBottomRight);
                }
            } else {
                uiRoomStore.Action_HangupAction.bindView(binding.llActionBottomCenter);
            }

            if (binding.llActionTopLeft.llContent.getVisibility() != View.VISIBLE
                    && binding.llActionTopCenter.llContent.getVisibility() != View.VISIBLE
                    && binding.llActionTopRight.llContent.getVisibility() != View.VISIBLE) {
                binding.msLlTop.setVisibility(View.GONE);
            } else {
                binding.msLlTop.setVisibility(View.VISIBLE);
            }
            if (binding.llActionBottomLeft.llContent.getVisibility() != View.VISIBLE
                    && binding.llActionBottomCenter.llContent.getVisibility() != View.VISIBLE
                    && binding.llActionBottomRight.llContent.getVisibility() != View.VISIBLE) {
                binding.msLlBottom.setVisibility(View.GONE);
            } else {
                binding.msLlBottom.setVisibility(View.VISIBLE);
            }
        });

        showUI(true);

    }

    public void hideAllAction() {
        for (MsLayoutActionBinding itemActionBinding : Arrays.asList(
                binding.llActionTopLeft,
                binding.llActionTopRight,
                binding.llActionTopCenter,
                binding.llActionBottomLeft,
                binding.llActionBottomRight,
                binding.llActionBottomCenter)) {
            itemActionBinding.llContent.setVisibility(View.INVISIBLE);
        }
    }


    Runnable tipDismiss = () -> {
        binding.msTvTip.setVisibility(View.GONE);
    };

    private void setTipText() {
        String tip = null;
        int delayDismissTime = 0;
        binding.msTvTip.removeCallbacks(tipDismiss);

        Pair<LocalConnectState, ConversationState> localState = uiRoomStore.localState.getValue();
        ConnectionState connectionState = target.getValue() == null ? null : target.getValue().connectionState.getValue();
        ConversationState conversationState = target.getValue() == null ? null : target.getValue().conversationState.getValue();

        if (localState != null) {
            if (localState.first == LocalConnectState.NEW || localState.first == LocalConnectState.CONNECTING) {
                tip = "连接中...";
            } else if (localState.first == LocalConnectState.DISCONNECTED || localState.first == LocalConnectState.RECONNECTING) {
                tip = "重连中...";
            } else {
                if (connectionState == ConnectionState.Offline) {
                    tip = "对方重连中...";
                } else {
                    if (conversationState == ConversationState.OfflineTimeout || conversationState == ConversationState.Left
                            // 本地自己操作挂断
                            || localState.second == ConversationState.InviteBusy
                            || localState.second == ConversationState.Left
                            || localState.second == ConversationState.OfflineTimeout
                            || localState.second == ConversationState.InviteReject
                            || localState.second == ConversationState.InviteTimeout) {
                        tip = "通话已结束";
                        showUI(true);
                    } else if (conversationState == ConversationState.Invited) {
                        tip = "等待接听";
                    } else if (conversationState == ConversationState.InviteTimeout) {
                        tip = "无人接听";
                    } else if (conversationState == ConversationState.InviteBusy) {
                        tip = "对方忙线";
                    } else if (conversationState == ConversationState.InviteReject) {
                        tip = "对方已挂断";
                    } else if (conversationState == ConversationState.Joined || localState.second == ConversationState.Joined) {
                        // 都join了就是开始通话
                        if (conversationState == ConversationState.Joined && localState.second == ConversationState.Joined) {
                            // 只有第一次才显示
                            if (uiRoomStore.activityBindCount == 1) {
                                tip = "通话中...";
                                delayDismissTime = 2000;
                            }
                        } else {
                            if (uiRoomStore.owner) {
                                tip = "等待对方接听...";
                            } else {
                                tip = "待接听";
                            }
                        }
                    }
                }
            }
        }

        if (tip != null) {
            binding.msTvTip.setText(tip);
            binding.msTvTip.setVisibility(View.VISIBLE);
        }
        if (delayDismissTime > 0)
            binding.msTvTip.postDelayed(tipDismiss, delayDismissTime);
    }

    boolean showUI = false;
    Runnable uiDismiss = () -> {
        showUI(false);
    };

    private void showUI(boolean show) {
        showUI = show;
        binding.msLlUser.removeCallbacks(uiDismiss);
        binding.msIvMinimize.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        binding.msLlUser.setVisibility(show && (uiRoomStore.audioOnly || uiRoomStore.callingActual.getValue() != Boolean.TRUE) ? View.VISIBLE : View.INVISIBLE);
        if (binding.msLlTop.getVisibility() != View.GONE) {
            binding.msLlTop.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        }
        if (binding.msLlBottom.getVisibility() != View.GONE) {
            binding.msLlBottom.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        }
        binding.msTvTime.setVisibility(show && uiRoomStore.calling.getValue() == Boolean.TRUE ? View.VISIBLE : View.GONE);
        if (show && uiRoomStore.calling.getValue() == Boolean.TRUE && !uiRoomStore.audioOnly) {
            binding.msLlUser.postDelayed(uiDismiss, 5000);
        }
    }

    private void resetRender() {
        ArchTaskExecutor.getInstance().postToMainThread(this::resetRender_, 200);
    }

    private synchronized void resetRender_() {
        if (uiRoomStore.audioOnly) {
            binding.msVRendererWindow.setVisibility(View.GONE);
            binding.msVRendererFull.setVisibility(View.GONE);
            return;
        }
        BuddyModel mime = uiRoomStore.mine.getValue();
        BuddyModel target = this.target.getValue();

        VideoTrack mimeVideoTrack = mime == null ? null : mime.videoTrack.getValue();
        VideoTrack targetVideoTrack = target == null ? null : target.videoTrack.getValue();
        boolean mimeTransportClosed = uiRoomStore.sendTransportState.getValue() == TransportState.disposed;
        boolean targetTransportClosed = uiRoomStore.recvTransportState.getValue() == TransportState.disposed;

        VideoTrack windowRenderTrack = mimeShowFullRender.getValue() ? targetVideoTrack : mimeVideoTrack;
        VideoTrack fullRenderTrack = mimeShowFullRender.getValue() ? mimeVideoTrack : targetVideoTrack;
        boolean windowTransportClosed = mimeShowFullRender.getValue() ? targetTransportClosed : mimeTransportClosed;
        boolean fullTransportClosed = mimeShowFullRender.getValue() ? mimeTransportClosed : targetTransportClosed;

        if (windowRenderTrack != null && fullRenderTrack == null) {
            fullRenderTrack = windowRenderTrack;
            windowRenderTrack = null;
            boolean temp = fullTransportClosed;
            fullTransportClosed = windowTransportClosed;
            windowTransportClosed = temp;
        }

        mimeShowFullRenderActual = mimeVideoTrack == null && targetVideoTrack == null ? null
                : fullRenderTrack == mimeVideoTrack;


        binding.msVRendererWindow.bind(this, !windowTransportClosed, windowRenderTrack);
        binding.msVRendererWindow.setVisibility(windowRenderTrack == null ? View.GONE : View.VISIBLE);
        binding.msVRendererWindow.setMirror(windowRenderTrack != null && windowRenderTrack == mimeVideoTrack && uiRoomStore.cameraFacingState.getValue() == CameraFacingState.front);

        binding.msVRendererFull.bind(this, !fullTransportClosed, fullRenderTrack);
        binding.msVRendererFull.setVisibility(fullRenderTrack == null ? View.GONE : View.VISIBLE);
        binding.msVRendererFull.setMirror(fullRenderTrack != null && fullRenderTrack == mimeVideoTrack && uiRoomStore.cameraFacingState.getValue() == CameraFacingState.front);
    }
}
