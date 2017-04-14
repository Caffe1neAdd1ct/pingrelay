import net.dv8tion.jda.JDA;
import net.dv8tion.jda.JDABuilder;
import net.dv8tion.jda.entities.Role;
import net.dv8tion.jda.entities.TextChannel;
import net.dv8tion.jda.events.Event;
import net.dv8tion.jda.events.ReadyEvent;
import net.dv8tion.jda.events.message.MessageReceivedEvent;
import net.dv8tion.jda.hooks.EventListener;
import org.jivesoftware.smack.AbstractXMPPConnection;
import org.jivesoftware.smack.ReconnectionManager;
import org.jivesoftware.smack.SmackConfiguration;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.chat.Chat;
import org.jivesoftware.smack.chat.ChatManager;
import org.jivesoftware.smack.chat.ChatManagerListener;
import org.jivesoftware.smack.chat.ChatMessageListener;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smackx.ping.PingManager;

import javax.security.auth.login.LoginException;
import java.util.List;
import java.util.Scanner;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PingRelay {

    private static PingRelay INSTANCE;

    private String pw;
    private String jid;
    private String token;
    private String channelId;
    private String serverRole;

    private JDA discordClient;
    private AbstractXMPPConnection xmppClient;
    private TextChannel channel;
    private Thread xmppThread;
    private Thread autoReconnectThread;

    private PingRelay(String token, String jid, String pw, String cid, String role) {
        this.token = token;
        this.jid = jid;
        this.pw = pw;
        this.channelId = cid;
        this.serverRole = role;
        this.loginDiscord();
    }

    public static PingRelay getInstance() {
        return PingRelay.INSTANCE;
    }

    public static void create(String token, String jid, String pw, String cid, String role) {
        if (PingRelay.INSTANCE != null)
            throw new IllegalStateException("Already initialized!");
        PingRelay.INSTANCE = new PingRelay(token, jid, pw, cid, role);
    }

    public static void main(String... args) {
        //SmackConfiguration.DEBUG = true;

        if (args.length < 3) {
            throw new IllegalArgumentException("Required arguments: token, jid, channel_id");
        }
        System.out.println("INFO: Warnings about SRV records or presence packets are harmless.");
        System.out.println("Please enter password for " + args[1]);
        System.out.print("> ");
        String pw = String.valueOf(System.console().readPassword());
        PingRelay.create(args[0], args[1], pw, args[2], args[3]);
    }

    private void loginDiscord() {
        try {
            JDABuilder builder = new JDABuilder().setBotToken(this.token);

            builder.addListener(new EventListener() {
                @Override
                public void onEvent(Event event) {
                    if (event instanceof ReadyEvent) {
                        PingRelay.getInstance().ready();

                    } else if (event instanceof MessageReceivedEvent) {
                        MessageReceivedEvent mevent = (MessageReceivedEvent) event;
                        net.dv8tion.jda.entities.Message message = ((MessageReceivedEvent) event).getMessage();
                        String content = message.getContent();

                        if (content.startsWith("!stop") || content.startsWith("!kill")) {
                            List<Role> roles = mevent.getGuild().getRolesForUser(message.getAuthor());
                            for (Role i: roles) {
                                if (i.getName().equals(serverRole)) {
                                    PingRelay.getInstance().shutdown();
                                }
                            }
                        }
                    }
                }
            });

            this.discordClient = builder.buildAsync();

        } catch (LoginException e) {
            e.printStackTrace();
        }
    }

    private void ready() {
        this.channel = PingRelay.getInstance().getDiscordClient().getTextChannelById(this.channelId);
        //this.channel.sendMessage("XMPP relay active! Send '!stop' to stop.");
        this.initXmpp();
        this.loginXmpp();
        this.autoReconnect();
        this.commandPrompt();
    }

    private void initXmpp() {
        PingManager.setDefaultPingInterval(-1); // disable built-in automatic pings
        ReconnectionManager.setEnabledPerDefault(false); // disable built-in reconnection
        this.xmppClient = new XMPPTCPConnection(this.jid, this.pw);

//        ConsoleHandler cm = new ConsoleHandler();
//        Logger.getLogger(PingManager.class.getName()).setLevel(Level.ALL);
//        Logger.getLogger(PingManager.class.getName()).addHandler(cm);
//        for (Handler h: Logger.getLogger(PingManager.class.getName()).getHandlers()) {
//            h.setLevel(Level.ALL);
//        }

        ChatManager chatManager = ChatManager.getInstanceFor(getXmppClient());
        chatManager.addChatListener(new ChatManagerListener() {
            @Override
            public void chatCreated(Chat chat, boolean createdLocally) {
                if (!createdLocally) {
                    chat.addMessageListener(new ChatMessageListener() {
                        @Override
                        public void processMessage(Chat chat, Message m) {
                            PingRelay.getInstance().messageReceived(m);
                        }
                    });
                }
            }
        });


    }

    private void loginXmpp() {
        try {
            this.xmppClient.connect();
            if (!this.xmppClient.isAuthenticated())
                this.xmppClient.login();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void messageReceived(Message m) {
        if (m.getFrom().startsWith("skynet") && m.getBody() != null) {
            this.getChannel().sendMessage("@everyone \n" + (m.getBody().replaceAll("\\|\\| ", "\n").replaceAll("\\s*\\*\\*\\*\\s*BROADCAST TO all\\s*\\*\\*\\*\\s*\\n", "")));
            System.out.println("Ping!");
        }
    }

    private void autoReconnect() {
        this.autoReconnectThread = new Thread(new Runnable() {
            @Override
            public void run() {
                PingManager pm = PingManager.getInstanceFor(PingRelay.getInstance().getXmppClient());
                AbstractXMPPConnection connection = PingRelay.getInstance().getXmppClient();
                try {
                    while (true) {
                        Thread.sleep(60000);
                        try {
                            if (connection.isAuthenticated() && pm.pingMyServer())
                                continue;
                        } catch (SmackException.NotConnectedException e) {
                            e.printStackTrace();
                        }
                        PingRelay.getInstance().getXmppClient().disconnect();
                        System.out.println("XMPP RECONNECT!");
                        Thread.sleep(3000);
                        PingRelay.getInstance().loginXmpp();
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    System.err.println("AUTORECONNECT INTERRUPTED!");
                }
            }
        });
        this.autoReconnectThread.setDaemon(false);
        this.autoReconnectThread.start();
    }

    private void commandPrompt() {
        this.xmppThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try (Scanner s = new Scanner(System.in)) {
                    PingRelay instance = PingRelay.getInstance();
                    Thread.sleep(3000);
                    String in;
                    System.out.println("Receiving Discord messages starting with '!stop' or '!kill' will halt the bot.");
                    System.out.println("Recognized commands: 'status', 'stop'.");
                    while (!(in = s.nextLine()).equals("stop")) {
                        if (in.equals("status")) {
                            System.out.println("Discord status:");
                            System.out.println(instance.getDiscordClient().getStatus());
                            System.out.println("XMPP Connected: " + instance.getXmppClient().isConnected());
                            System.out.println("XMPP Authenticated: " + instance.getXmppClient().isAuthenticated());
                        }
                    }
                    // read stop
                    instance.shutdown();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        this.xmppThread.setDaemon(true);
        this.xmppThread.start();
    }

    public JDA getDiscordClient() {
        return this.discordClient;
    }

    public TextChannel getChannel() {
        return this.channel;
    }

    public AbstractXMPPConnection getXmppClient() {
        return this.xmppClient;
    }

    private void shutdown() {
        this.getXmppClient().disconnect();
        this.getDiscordClient().shutdown();
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            System.exit(0);
        }
    }

}
