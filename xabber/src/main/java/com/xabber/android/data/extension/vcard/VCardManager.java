/**
 * Copyright (c) 2013, Redsolution LTD. All rights reserved.
 *
 * This file is part of Xabber project; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License, Version 3.
 *
 * Xabber is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License,
 * along with this program. If not, see http://www.gnu.org/licenses/.
 */
package com.xabber.android.data.extension.vcard;

import android.database.Cursor;

import com.xabber.android.data.Application;
import com.xabber.android.data.log.LogManager;
import com.xabber.android.data.NetworkException;
import com.xabber.android.data.OnLoadListener;
import com.xabber.android.data.SettingsManager;
import com.xabber.android.data.account.AccountItem;
import com.xabber.android.data.account.AccountManager;
import com.xabber.android.data.account.listeners.OnAccountRemovedListener;
import com.xabber.android.data.connection.ConnectionItem;
import com.xabber.android.data.connection.ConnectionManager;
import com.xabber.android.data.connection.listeners.OnPacketListener;
import com.xabber.android.data.database.sqlite.VCardTable;
import com.xabber.android.data.entity.AccountJid;
import com.xabber.android.data.entity.UserJid;
import com.xabber.android.data.extension.avatar.AvatarManager;
import com.xabber.android.data.extension.blocking.BlockingManager;
import com.xabber.android.data.extension.muc.MUCManager;
import com.xabber.android.data.roster.OnRosterChangedListener;
import com.xabber.android.data.roster.OnRosterReceivedListener;
import com.xabber.android.data.roster.PresenceManager;
import com.xabber.android.data.roster.RosterContact;
import com.xabber.android.data.roster.RosterManager;
import com.xabber.android.data.roster.StructuredName;
import com.xabber.xmpp.vcard.VCardProperty;

import org.jivesoftware.smack.AbstractXMPPConnection;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.packet.IQ.Type;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.packet.XMPPError;
import org.jivesoftware.smackx.vcardtemp.packet.VCard;
import org.jxmpp.jid.BareJid;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.stringprep.XmppStringprepException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Manage vCards and there requests.
 *
 * @author alexander.ivanov
 */
public class VCardManager implements OnLoadListener, OnPacketListener,
        OnRosterReceivedListener, OnAccountRemovedListener {

    private static final StructuredName EMPTY_STRUCTURED_NAME = new StructuredName(
            null, null, null, null, null);

    /**
     * Nick and formatted names for the users.
     */
    private final Map<Jid, StructuredName> names;

    /**
     * List of accounts which requests its avatar in order to avoid subsequence
     * requests.
     */
    private final ArrayList<AccountJid> accountRequested;

    private static VCardManager instance;

    @SuppressWarnings("WeakerAccess")
    Set<Jid> vCardRequests = new ConcurrentSkipListSet<>();
    @SuppressWarnings("WeakerAccess")
    Set<AccountJid> vCardSaveRequests = new ConcurrentSkipListSet<>();

    public static VCardManager getInstance() {
        if (instance == null) {
            instance = new VCardManager();
        }

        return instance;
    }

    private VCardManager() {
        names = new HashMap<>();
        accountRequested = new ArrayList<>();
    }

    @Override
    public void onLoad() {
        final Map<Jid, StructuredName> names = new HashMap<>();
        Cursor cursor = VCardTable.getInstance().list();
        try {
            if (cursor.moveToFirst()) {
                do {
                    try {
                        names.put(
                                JidCreate.from(VCardTable.getUser(cursor)),
                                new StructuredName(VCardTable.getNickName(cursor),
                                        VCardTable.getFormattedName(cursor),
                                        VCardTable.getFirstName(cursor), VCardTable
                                        .getMiddleName(cursor), VCardTable
                                        .getLastName(cursor)));
                    } catch (XmppStringprepException e) {
                        LogManager.exception(this, e);
                    }
                } while (cursor.moveToNext());
            }
        } finally {
            cursor.close();
        }
        Application.getInstance().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                onLoaded(names);
            }
        });
    }

    @SuppressWarnings("WeakerAccess")
    void onLoaded(Map<Jid, StructuredName> names) {
        this.names.putAll(names);
    }

    @Override
    public void onRosterReceived(AccountItem accountItem) {
        AccountJid account = accountItem.getAccount();
        if (!accountRequested.contains(account) && SettingsManager.connectionLoadVCard()) {
            BareJid bareAddress = accountItem.getRealJid().asBareJid();
            if (bareAddress != null && !names.containsKey(bareAddress)) {
                request(account, bareAddress);
                accountRequested.add(account);
            }
        }

        Collection<UserJid> blockedContacts = BlockingManager.getInstance().getBlockedContacts(account);

        // Request vCards for new contacts.
        for (RosterContact contact : RosterManager.getInstance().getContacts()) {
            if (account.equals(contact.getAccount()) && !names.containsKey(contact.getUser().getJid())) {
                if (!blockedContacts.contains(contact.getUser())) {
                    request(account, contact.getUser().getJid());
                }
            }
        }
    }

    @Override
    public void onAccountRemoved(AccountItem accountItem) {
        accountRequested.remove(accountItem.getAccount());
    }

    /**
     * Requests vCard.
     */
    public void request(AccountJid account, Jid jid) {
        requestVCard(account, jid);
    }

    /**
     * Get uses's nick name.
     *
     * @return first specified value:
     * <ul>
     * <li>nick name</li>
     * <li>formatted name</li>
     * <li>empty string</li>
     * </ul>
     */
    public String getName(Jid jid) {
        StructuredName name = names.get(jid);
        if (name == null)
            return "";
        return name.getBestName();
    }

    /**
     * Get uses's name information.
     *
     * @return <code>null</code> if there is no info.
     */
    public StructuredName getStructuredName(Jid jid) {
        return names.get(jid);
    }

    @SuppressWarnings("WeakerAccess")
    void onVCardReceived(final AccountJid account, final Jid bareAddress, final VCard vCard) {
        final StructuredName name;
        if (vCard.getType() == Type.error) {
            onVCardFailed(account, bareAddress);
            if (names.containsKey(bareAddress)) {
                return;
            }
            name = EMPTY_STRUCTURED_NAME;
        } else {
            for (OnVCardListener listener : Application.getInstance().getUIListeners(OnVCardListener.class)) {
                listener.onVCardReceived(account, bareAddress, vCard);
            }

            try {
                String hash = vCard.getAvatarHash();
                byte[] avatar = vCard.getAvatar();
                AvatarManager.getInstance().onAvatarReceived(bareAddress, hash, avatar);
                // "bad base-64" error happen sometimes
            } catch (IllegalArgumentException e) {
                LogManager.exception(this, e);
            }

            name = new StructuredName(vCard.getNickName(), vCard.getField(VCardProperty.FN.name()),
                    vCard.getFirstName(), vCard.getMiddleName(), vCard.getLastName());

            Application.getInstance().runInBackground(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (account.getFullJid().asBareJid().equals(bareAddress.asBareJid())) {
                            PresenceManager.getInstance().resendPresence(account);
                        }
                    } catch (NetworkException e) {
                        LogManager.exception(this, e);
                    }
                }
            });

        }
        names.put(bareAddress, name);
        for (RosterContact rosterContact : RosterManager.getInstance().getContacts()) {
            if (rosterContact.getUser().equals(bareAddress)) {
                for (OnRosterChangedListener listener : Application.getInstance()
                        .getManagers(OnRosterChangedListener.class)) {
                    listener.onContactStructuredInfoChanged(rosterContact, name);
                }
            }
        }
        Application.getInstance().runInBackground(new Runnable() {
            @Override
            public void run() {
                VCardTable.getInstance().write(bareAddress.toString(), name);
            }
        });
        if (vCard.getFrom() == null) { // account it self
            AccountManager.getInstance().onAccountChanged(account);
        } else {
            try {
                RosterManager.onContactChanged(account, UserJid.from(bareAddress));
            } catch (UserJid.UserJidCreateException e) {
                LogManager.exception(this, e);
            }
        }
    }

    @SuppressWarnings("WeakerAccess")
    void onVCardFailed(final AccountJid account, final Jid bareAddress) {
        for (OnVCardListener listener : Application.getInstance().getUIListeners(OnVCardListener.class)) {
            listener.onVCardFailed(account, bareAddress);
        }
    }

    @SuppressWarnings("WeakerAccess")
    void onVCardSaveSuccess(AccountJid account) {
        for (OnVCardSaveListener listener : Application.getInstance().getUIListeners(OnVCardSaveListener.class)) {
            listener.onVCardSaveSuccess(account);
        }
    }

    @SuppressWarnings("WeakerAccess")
    void onVCardSaveFailed(AccountJid account) {
        for (OnVCardSaveListener listener : Application.getInstance().getUIListeners(OnVCardSaveListener.class)) {
            listener.onVCardSaveFailed(account);
        }
    }

    @Override
    public void onStanza(ConnectionItem connection, Stanza stanza) {
        if (!(connection instanceof AccountItem)) {
            return;
        }
        AccountJid account = connection.getAccount();
        if (stanza instanceof Presence && ((Presence) stanza).getType() != Presence.Type.error) {
            Jid from = stanza.getFrom();

            if (from == null) {
                return;
            }

            Jid addressForVcard = from;

            if (MUCManager.getInstance().hasRoom(account, from.asEntityBareJidIfPossible())) {
                addressForVcard = from;
            }

            // Request vCard for new users
            if (!names.containsKey(addressForVcard)) {
                if (SettingsManager.connectionLoadVCard()) {
                    request(account, addressForVcard);
                }
            }
        }
    }

    private void requestVCard(final AccountJid account, final Jid srcUser) {

        final AccountItem accountItem = AccountManager.getInstance().getAccount(account);
        if (accountItem == null) {
            return;
        }

        if (!accountItem.getFactualStatusMode().isOnline()) {
            onVCardFailed(account, srcUser);
            return;
        }

        final org.jivesoftware.smackx.vcardtemp.VCardManager vCardManager
                = org.jivesoftware.smackx.vcardtemp.VCardManager.getInstanceFor(accountItem.getConnection());


        Application.getInstance().runInBackground(new Runnable() {
            @Override
            public void run() {
                if (!accountItem.getConnection().isAuthenticated()) {
                    return;
                }

                VCard vCard = null;

                Collection<UserJid> blockedContacts = BlockingManager.getInstance().getBlockedContacts(account);
                for (UserJid blockedContact : blockedContacts) {
                    if (blockedContact.getBareJid().equals(srcUser.asBareJid())) {
                        return;
                    }
                }

                final EntityBareJid entityBareJid = srcUser.asEntityBareJidIfPossible();

                if (entityBareJid != null) {
                    vCard = getvCard(entityBareJid);
                }

                final VCard finalVCard = vCard;
                Application.getInstance().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (finalVCard == null) {
                            onVCardFailed(account, srcUser);
                        } else {
                            onVCardReceived(account, srcUser, finalVCard);
                        }
                    }
                });
            }

            VCard getvCard(EntityBareJid entityBareJid) {
                VCard vCard = null;
                vCardRequests.add(srcUser);
                try {
                    vCard = vCardManager.loadVCard(entityBareJid);
                } catch (SmackException.NoResponseException | SmackException.NotConnectedException e) {
                    LogManager.exception(this, e);
                    LogManager.w(this, "Error getting vCard: " + e.getMessage());
                } catch (XMPPException.XMPPErrorException e ) {
                    LogManager.exception(this, e);
                    LogManager.w(this, "XMPP error getting vCard: " + e.getMessage() + e.getXMPPError());

                    if (e.getXMPPError().getCondition() == XMPPError.Condition.item_not_found) {
                        vCard = new VCard();
                    }

                } catch (ClassCastException e) {
                    LogManager.exception(this, e);
                    // http://stackoverflow.com/questions/31498721/error-loading-vcard-information-using-smack-emptyresultiq-cannot-be-cast-to-or
                    LogManager.w(this, "ClassCastException: " + e.getMessage());
                    vCard = new VCard();
                } catch (InterruptedException e) {
                    LogManager.exception(this, e);
                }
                vCardRequests.remove(srcUser);
                return vCard;
            }
        });
    }

    public void saveVCard(final AccountJid account, final VCard vCard) {
        AccountItem accountItem = AccountManager.getInstance().getAccount(account);
        if (accountItem == null) {
            onVCardSaveFailed(account);
            return;
        }

        final AbstractXMPPConnection xmppConnection = accountItem.getConnection();
        final org.jivesoftware.smackx.vcardtemp.VCardManager vCardManager = org.jivesoftware.smackx.vcardtemp.VCardManager.getInstanceFor(xmppConnection);

        Application.getInstance().runInBackground(new Runnable() {
            @Override
            public void run() {

                boolean isSuccess = true;

                xmppConnection.setPacketReplyTimeout(120000);

                vCardSaveRequests.add(account);
                try {
                    vCardManager.saveVCard(vCard);
                    String avatarHash = null;

                    try {
                        avatarHash = vCard.getAvatarHash();
                        // "bad base-64" error happen sometimes
                    } catch (IllegalArgumentException e) {
                        LogManager.exception(this, e);
                    }
                    if (avatarHash == null) {
                        avatarHash = AvatarManager.EMPTY_HASH;
                    }
                    PresenceManager.getInstance().sendVCardUpdatePresence(account, avatarHash);
                } catch (SmackException.NoResponseException | XMPPException.XMPPErrorException
                        | SmackException.NotConnectedException | NetworkException | InterruptedException e) {
                    LogManager.w(this, "Error saving vCard: " + e.getMessage());
                    isSuccess = false;
                }
                vCardSaveRequests.remove(account);

                xmppConnection.setPacketReplyTimeout(ConnectionManager.PACKET_REPLY_TIMEOUT);

                final boolean finalIsSuccess = isSuccess;
                Application.getInstance().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (finalIsSuccess) {
                            onVCardSaveSuccess(account);
                        } else {
                            onVCardSaveFailed(account);
                        }
                    }
                });
            }
        });
    }

    public boolean isVCardRequested(Jid user) {
        return vCardRequests.contains(user.asBareJid());
    }

    public boolean isVCardSaveRequested(AccountJid account) {
        return vCardSaveRequests.contains(account);
    }

}
