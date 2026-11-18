package com.discord.socialsdk.rpc;

import com.discord.socialsdk.rpc.IDiscordRpcCallback;
import com.discord.socialsdk.rpc.IDiscordRpcConnection;

interface IDiscordRpcService {
    IDiscordRpcConnection connect(long appId, String version, IDiscordRpcCallback callback);
}
