/*
 * Copyright (C) 2017 Kai-Chung Yan (殷啟聰)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License, or any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package chat.viska.android.demo;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.ListActivity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.support.design.widget.Snackbar;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Toast;
import chat.viska.R;
import chat.viska.android.XmppService;
import chat.viska.commons.DisposablesBin;
import chat.viska.commons.reactive.MutableReactiveObject;
import chat.viska.xmpp.Jid;
import chat.viska.xmpp.Session;
import chat.viska.xmpp.plugins.BasePlugin;
import chat.viska.xmpp.plugins.DiscoItem;
import chat.viska.xmpp.plugins.RosterItem;
import chat.viska.xmpp.plugins.webrtc.WebRtcPlugin;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.MaybeSubject;
import java.util.Random;
import javax.annotation.Nonnull;

public class MainActivity extends ListActivity {

  private final MaybeSubject<Session> session = MaybeSubject.create();
  private final DisposablesBin bin = new DisposablesBin();
  private final MutableReactiveObject<Boolean> calling = new MutableReactiveObject<>(false);
  private Snackbar snackbar;
  private Disposable callSubscription;
  private Jid localJid = Jid.EMPTY;
  private int requestCode;

  private final ServiceConnection binding = new ServiceConnection() {

    @Override
    public void onServiceConnected(@Nonnull final ComponentName componentName,
                                   @Nonnull final IBinder binder) {
      final XmppService xmpp = ((XmppService.Binder) binder).getService();
      bin.add(
          xmpp.isSyncingAccounts().getStream().filter(it -> !it).firstOrError().subscribe(
              it -> session.onSuccess(xmpp.getSessions().get(localJid))
          )
      );
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {}
  };

  private final AdapterView.OnItemClickListener onItemClickListener = (
      adapterView, view, position, id
  ) -> {
    calling.changeValue(true);
    bin.add(session.subscribe(session -> {
      final BasePlugin plugin = session.getPluginManager().getPlugin(BasePlugin.class);
      callSubscription = plugin.queryDiscoItems(
          (Jid) getListView().getItemAtPosition(position),
          null
      ).flattenAsObservable(it -> it).filter(
          it -> it.getNode().isEmpty()
      ).map(DiscoItem::getJid).observeOn(Schedulers.io()).filter(
          it -> checkIfCallable(it, plugin)
      ).firstElement().observeOn(AndroidSchedulers.mainThread()).doOnComplete(() -> {
        calling.changeValue(false);
        Toast.makeText(this, "No available client found", Toast.LENGTH_LONG).show();
      }).subscribe(it -> {
        final Intent intent = new Intent(this, CallingActivity.class);
        intent.setAction(CallingActivity.ACTION_CALL_OUTBOUND);
        intent.putExtra(CallingActivity.EXTRA_LOCAL_JID, localJid.toString());
        intent.setData(Uri.fromParts("xmpp", it.toString(), null));
        requestCode = new Random().nextInt();
        startActivityForResult(intent, requestCode);
      }, ex -> {
        Toast.makeText(this, ex.getLocalizedMessage(), Toast.LENGTH_LONG).show();
      });
    }));
  };

  private final Snackbar.Callback snackbarCallback = new Snackbar.Callback() {
    @Override
    public void onDismissed(Snackbar transientBottomBar, int event) {
      super.onDismissed(transientBottomBar, event);
      if (callSubscription != null) {
        callSubscription.dispose();
      }
      calling.setValue(false);
    }
  };

  private boolean checkIfCallable(@Nonnull final Jid jid, @Nonnull final BasePlugin plugin) {
    return !jid.equals(localJid)
        && plugin.queryDiscoInfo(jid).blockingGet().getFeatures().contains(WebRtcPlugin.XMLNS);
  }

  private void refresh() {
    bin.add(session.subscribe(session -> {
      if (localJid.isEmpty()) {
        setListAdapter(null);
        return;
      }
      final Disposable subscription = session
          .getPluginManager()
          .getPlugin(BasePlugin.class)
          .queryRoster()
          .flattenAsObservable(list -> list)
          .map(RosterItem::getJid)
          .toList()
          .subscribeOn(Schedulers.io())
          .observeOn(AndroidSchedulers.mainThread())
          .subscribe(
              list -> {
                list.add(0, localJid);
                setListAdapter(new ArrayAdapter<>(this, R.layout.demo_roster_item, list));
              },
              cause -> Toast.makeText(
                  this, "Failed to retrieve roster.", Toast.LENGTH_LONG
              ).show()
          );
      bin.add(subscription);
    }));
  }

  @Override
  protected void onCreate(final Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    snackbar = Snackbar.make(
        getListView(),
        R.string.searching_for_available_clients,
        Snackbar.LENGTH_INDEFINITE
    );
    snackbar.addCallback(snackbarCallback);
    snackbar.setAction(
        R.string.title_cancel,
        view -> snackbarCallback.onDismissed(snackbar, Snackbar.Callback.DISMISS_EVENT_ACTION)
    );
    calling.getStream().observeOn(AndroidSchedulers.mainThread()).subscribe(calling -> {
      if (calling) {
        getListView().setEnabled(false);
        snackbar.show();
      } else {
        snackbar.dismiss();
        getListView().setEnabled(true);
      }
    });

    final Account[] accounts = AccountManager.get(this).getAccountsByType(
        getString(R.string.api_account_type)
    );
    localJid = accounts.length == 0 ? Jid.EMPTY : new Jid(accounts[0].name);
    if (localJid.isEmpty()) {
      session.onComplete();
    } else {
      bindService(new Intent(this, XmppService.class), binding, BIND_AUTO_CREATE);
    }
    refresh();
    getListView().setOnItemClickListener(onItemClickListener);
  }

  @Override
  protected void onDestroy() {
    if (session.hasValue()) {
      unbindService(binding);
    }
    bin.clear();
    if (callSubscription != null) {
      callSubscription.dispose();
    }
    super.onDestroy();
  }

  @Override
  protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == this.requestCode) {
      calling.changeValue(false);
    }
  }
}
