package kml;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import kml.enums.VersionType;
import kml.exceptions.AuthenticationException;
import kml.exceptions.DownloaderException;
import kml.exceptions.GameLauncherException;
import kml.exceptions.WebLauncherException;
import kml.objects.Profile;
import kml.objects.RequestBody;
import kml.objects.User;
import kml.objects.VersionMeta;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * @website https://krothium.com
 * @author DarkLBP
 */
class WebHandler implements HttpHandler {
    private final Kernel kernel;
    private final Console console;
    
    public WebHandler(Kernel k){
        this.kernel = k;
        this.console = k.getConsole();
    }
    @Override
    public void handle(HttpExchange he) throws IOException {
        try {
            String path = he.getRequestURI().toString();
            String method = he.getRequestMethod();

            ByteArrayOutputStream responseData = new ByteArrayOutputStream();
            int responseCode = 200;
            OutputStream out = he.getResponseBody();
            Map<String, List<String>> responseHeaders = new HashMap<>();
            if (method.equalsIgnoreCase("GET")){
                if (path.equals("/")){
                    responseCode = 301;
                    List<String> locationValues = new ArrayList<>();
                    if (kernel.checkForUpdates()){
                        locationValues.add("/update.html");
                    } else if (kernel.isAuthenticated()){
                        locationValues.add("/play.html");
                    } else {
                        locationValues.add("/login.html");
                    }
                    responseHeaders.put("Location", locationValues);
                }else{
                    String finalPath = (path.contains("?") ? path.split("\\?")[0] : path);
                    File abstractFile = new File(finalPath);
                    String fileName = abstractFile.getName();
                    String extension = Utils.getExtension(fileName);
                    InputStream s;
                    if (extension.equalsIgnoreCase("html") && !fileName.equalsIgnoreCase("login.html") && !fileName.equalsIgnoreCase("update.html") && !kernel.isAuthenticated()){
                        responseCode = 301;
                        List<String> locationValues = new ArrayList<>();
                        locationValues.add("/login.html");
                        responseHeaders.put("Location", locationValues);
                    } else if (extension.equalsIgnoreCase("html") && fileName.equalsIgnoreCase("login.html") && !fileName.equalsIgnoreCase("update.html") && kernel.isAuthenticated()) {
                        responseCode = 301;
                        List<String> locationValues = new ArrayList<>();
                        locationValues.add("/play.html");
                        responseHeaders.put("Location", locationValues);
                    } else {
                        s = WebLauncher.class.getResourceAsStream("/kml/web" + finalPath);
                        if (s == null){
                            responseCode = 400;
                        } else {
                            try{
                                int i;
                                byte[] buffer = new byte[4096];
                                while((i=s.read(buffer))!=-1){
                                   responseData.write(buffer, 0, i);
                                }
                                s.close();
                            } catch (Exception ex){
                                responseCode = 500;
                            }
                            if (extension.equalsIgnoreCase("html") || extension.equalsIgnoreCase("js")){
                                try (InputStream l = WebLauncher.class.getResourceAsStream("/kml/web/lang/" + Constants.LANG_CODE + "/" + fileName.replace("." + extension, ""))){
                                    if (l != null) {
                                        String dataRaw = new String(responseData.toByteArray(), "UTF-8");
                                        BufferedReader reader = new BufferedReader(new InputStreamReader(l, "UTF-8"));
                                        String line;
                                        while ((line = reader.readLine()) != null){
                                            dataRaw = dataRaw.replaceFirst("\\{%s}", line);
                                        }
                                        reader.close();
                                        responseData.reset();
                                        responseData.write(dataRaw.getBytes("UTF-8"));
                                    }
                                }
                            }
                            if (extension.equalsIgnoreCase("html")){
                                List<String> ctv = new ArrayList<>();
                                ctv.add("text/html");
                                responseHeaders.put("Content-Type", ctv);
                            }
                        }
                    }
                }                
            } else if (method.equalsIgnoreCase("POST")){
                List<String> ctv = new ArrayList<>();
                ctv.add("text/plain");
                responseHeaders.put("Content-Type", ctv);
                if (path.startsWith("/action/")){
                    StringBuilder response = new StringBuilder();
                    String function = path.replace("/action/", "");
                    RequestBody requestBody = new RequestBody();
                    Headers hs = he.getRequestHeaders();
                    int contentLength = -1;
                    if (hs.containsKey("Content-Length")){
                        List<String> clh = hs.get("Content-Length");
                        contentLength = Integer.parseInt(clh.get(0));
                        if (contentLength > 0){
                            InputStream in = he.getRequestBody();
                            ByteArrayOutputStream raw = new ByteArrayOutputStream();
                            for (int i = 0; i < contentLength; i++){
                                raw.write(in.read());
                            }
                            requestBody = new RequestBody(raw.toByteArray());
                        }
                    }
                    String contentType = null;
                    if (hs.containsKey("Content-Type")){
                        List<String> cth = hs.get("Content-Type");
                        contentType = cth.get(0);
                    }
                    String contentExtra = null;
                    if (hs.containsKey("Content-Extra")){
                        List<String> ceh = hs.get("Content-Extra");
                        contentExtra = ceh.get(0);
                    }
                    String profile;
                    Map<String, String> params = new HashMap<>();
                    User user;
                    Profile prof;
                    switch (function){
                        case "authenticate":
                            if (contentLength > 0){
                                String[] userArray = requestBody.getString().split(":");
                                if (userArray.length != 2){
                                    throw new WebLauncherException(path, 400, out);
                                }
                                String userName = Utils.fromBase64(userArray[0]);
                                String password = Utils.fromBase64(userArray[1]);
                                try{
                                    kernel.authenticate(userName, password);
                                    kernel.saveProfiles();
                                    response.append("OK");
                                }catch (AuthenticationException ex){
                                    response.append(ex.getMessage());
                                }
                            } else {
                                throw new WebLauncherException(path, 400, out);
                            }
                            break;
                        case "play":
                            Thread t = new Thread(){
                                @Override
                                public void run(){
                                    try{
                                        kernel.download();
                                        kernel.launchGame();
                                    } catch (GameLauncherException | DownloaderException ex){
                                        console.printError(ex.getMessage());
                                    }
                                }
                            };
                            t.start();
                            break;
                        case "status":
                            if (kernel.isDownloading()){
                                response.append("1");
                            } else if (kernel.isGameStarted()){
                                response.append("2");
                            } else if (kernel.hasGameCrashed()){
                                response.append("3");
                            } else if (Constants.USE_LOCAL) {
                                response.append("4");
                            } else {
                                response.append("0");
                            }
                            response.append(":");
                            response.append(String.valueOf(kernel.getDownloadProgress()));
                            break;
                        case "signature":
                            response.append("Krothium Minecraft Launcher v" + Constants.KERNEL_BUILD_NAME);
                            break;
                        case "logout":
                            if (kernel.logOut()){
                                kernel.saveProfiles();
                                response.append("OK");
                            }
                            break;
                        case "profiles":
                            Map<String, Profile> p = kernel.getProfileDB();
                            Set keys = p.keySet();
                            Iterator i = keys.iterator();
                            while (i.hasNext()){
                                response.append(Utils.toBase64(i.next().toString()));
                                if (i.hasNext()){
                                    response.append(":");
                                }
                            }
                            break;
                        case "selectedprofile":
                            response.append(Utils.toBase64(kernel.getSelectedProfile()));
                            break;
                        case "setselectedprofile":
                            profile = Utils.fromBase64(requestBody.getString());
                            if (profile != null){
                                if (kernel.existsProfile(profile)){
                                    if (kernel.setSelectedProfile(profile)){
                                        response.append("OK");
                                        kernel.saveProfiles();
                                    }
                                }
                            }
                            break;
                        case "selectedversion":
                            prof = kernel.getProfile(kernel.getSelectedProfile());
                            if (prof.hasVersion()){
                                response.append(Utils.toBase64(Utils.toBase64(prof.getVersionID()))).append(":").append(Utils.toBase64(prof.getVersionID()));
                            } else {
                                response.append(Utils.toBase64("latest")).append(":").append(Utils.toBase64(kernel.getLatestVersion()));
                            }
                            break;
                        case "deleteprofile":
                            profile = Utils.fromBase64(requestBody.getString());
                            if (kernel.existsProfile(profile)){
                                if (kernel.deleteProfile(profile)){
                                    response.append("OK");
                                    kernel.saveProfiles();
                                }
                            }
                            break;
                        case "versions":
                            LinkedHashSet<String> v = kernel.getVersionDB();
                            Iterator vi = v.iterator();
                            List<VersionType> allowedTypes = new ArrayList<>();
                            if (contentLength > 0){
                                String[] types = requestBody.getString().split(":");
                                if (types.length != 3){
                                    throw new WebLauncherException(path, 400, out);
                                }
                                boolean snapshot = Boolean.valueOf(Utils.fromBase64(types[0]));
                                boolean oldBeta = Boolean.valueOf(Utils.fromBase64(types[1]));
                                boolean oldAlpha = Boolean.valueOf(Utils.fromBase64(types[2]));
                                allowedTypes.add(VersionType.RELEASE);
                                if (snapshot){
                                    allowedTypes.add(VersionType.SNAPSHOT);
                                }
                                if (oldBeta){
                                    allowedTypes.add(VersionType.OLD_BETA);
                                }
                                if (oldAlpha){
                                    allowedTypes.add(VersionType.OLD_ALPHA);
                                }
                            } else {
                                prof = kernel.getProfile(kernel.getSelectedProfile());
                                allowedTypes = prof.getAllowedVersionTypes();
                            }
                            response.append("latest");
                            while (vi.hasNext()){
                                String index = vi.next().toString();
                                VersionMeta version = kernel.getVersionMeta(index);
                                if (allowedTypes.contains(version.getType())){
                                    response.append(":").append(Utils.toBase64(version.getID()));
                                }
                            }
                            break;
                        case "saveprofile":
                            String[] profileArray = requestBody.getString().split(":");
                            if (profileArray.length != 10){
                                throw new WebLauncherException(path, 400, out);
                            }
                            if (profileArray[1].equals("noset")){
                                response.append("Profile name cannot be blank.");
                            } else {
                                String profileName = Utils.fromBase64(profileArray[0]);
                                String profileNameNew = Utils.fromBase64(profileArray[1]);
                                String profileVersion;
                                if (profileArray[2].equals("latest")){
                                    profileVersion = null;
                                } else {
                                    profileVersion = Utils.fromBase64(profileArray[2]);
                                }
                                boolean snapshot = Boolean.valueOf(Utils.fromBase64(profileArray[3]));
                                boolean oldBeta = Boolean.valueOf(Utils.fromBase64(profileArray[4]));
                                boolean oldAlpha = Boolean.valueOf(Utils.fromBase64(profileArray[5]));
                                String gameDir = (profileArray[6].equals("noset") ? "" : Utils.fromBase64(profileArray[6]));
                                String resolution = (profileArray[7].equals("noset") ? "" : Utils.fromBase64(profileArray[7]));
                                String javaExec = (profileArray[8].equals("noset") ? "" : Utils.fromBase64(profileArray[8]));
                                String javaArgs = (profileArray[9].equals("noset") ? "" : Utils.fromBase64(profileArray[9]));
                                if (!kernel.existsProfile(profileName) && !profileArray[0].equals("noset")){
                                    response.append("Profile ").append(profileName).append(" is specified but does not exist.");
                                } else {
                                    if (profileArray[0].equals("noset")){
                                        prof = new Profile(profileNameNew);
                                    } else {
                                        prof = kernel.getProfile(profileName);
                                    }
                                    boolean error = false;
                                    if (profileVersion == null){
                                        prof.setVersionID(null);
                                    } else if (!kernel.existsVersion(profileVersion)){
                                        error = true;
                                        response.append("Selected version ").append(profileVersion).append(" does not exist.").append("\n");
                                    } else {
                                        prof.setVersionID(profileVersion);
                                    }
                                    if (!gameDir.isEmpty()){
                                        File dir = new File(gameDir);
                                        prof.setGameDir(dir);
                                    } else {
                                        prof.setGameDir(null);
                                    }
                                    if (!resolution.isEmpty()){
                                        try{
                                            int x = Integer.parseInt(resolution.split("x")[0]);
                                            int y = Integer.parseInt(resolution.split("x")[1]);
                                            prof.setResolution(x, y);
                                        } catch (Exception ex){
                                            error = true;
                                            response.append("Invalid resolution values." + "\n");
                                        }
                                    }
                                    else{
                                        prof.setResolution(-1, -1);
                                    }
                                    if (!javaExec.isEmpty()){
                                        File file = new File(javaExec);
                                        if (file.exists()){
                                            if (file.isFile()){
                                                prof.setJavaDir(file); 
                                            }
                                            else {
                                                error = true;
                                                response.append("Invalid java executable file." + "\n");
                                            }
                                        } else {
                                            error = true;
                                            response.append("Java executable does not exist." + "\n");
                                        }
                                    } else {
                                        prof.setJavaDir(null);
                                    }
                                    if (!javaArgs.isEmpty()){
                                        prof.setJavaArgs(javaArgs);
                                    } else {
                                        prof.setJavaArgs(null);
                                    }
                                    if (snapshot){
                                        prof.allowVersionType(VersionType.SNAPSHOT);
                                    } else {
                                        prof.removeVersionType(VersionType.SNAPSHOT);
                                    }
                                    if (oldBeta){
                                        prof.allowVersionType(VersionType.OLD_BETA);
                                    } else {
                                        prof.removeVersionType(VersionType.OLD_BETA);
                                    }
                                    if (oldAlpha){
                                        prof.allowVersionType(VersionType.OLD_ALPHA);
                                    } else {
                                        prof.removeVersionType(VersionType.OLD_ALPHA);
                                    }
                                    if (!profileArray[0].equals("noset")){
                                        if (!profileName.equals(profileNameNew) && !profileArray[0].equals("noset")){
                                            kernel.renameProfile(profileName, profileNameNew);
                                        }
                                        if (!error){
                                            response.append("OK");
                                            kernel.saveProfiles();
                                        }
                                    } else {
                                        if (!error){
                                            if (!kernel.existsProfile(profileNameNew)){
                                                kernel.addProfile(prof);
                                                response.append("OK");
                                                kernel.saveProfiles();
                                            } else {
                                                response.append("Profile ").append(profileNameNew).append(" already exists.");
                                            }
                                        }
                                    }
                                }
                            }
                            break;
                        case "profiledata":
                            profile = Utils.fromBase64(requestBody.getString());
                            if (kernel.existsProfile(profile)){
                                prof = kernel.getProfile(profile);
                                response.append(Utils.toBase64(prof.getName())).append(":");
                                response.append(prof.hasVersion() ? Utils.toBase64(prof.getVersionID()) : "latest").append(":");
                                response.append(Utils.toBase64((String.valueOf(prof.isAllowedVersionType(VersionType.SNAPSHOT))))).append(":");
                                response.append(Utils.toBase64(String.valueOf(prof.isAllowedVersionType(VersionType.OLD_BETA)))).append(":");
                                response.append(Utils.toBase64(String.valueOf(prof.isAllowedVersionType(VersionType.OLD_ALPHA)))).append(":");
                                response.append(prof.hasGameDir() ? Utils.toBase64(prof.getGameDir().getAbsolutePath()) : "noset").append(":");
                                response.append(prof.hasResolution() ? Utils.toBase64(String.valueOf(prof.getResolutionWidth()) + "x" + String.valueOf(prof.getResolutionHeight())) : "noset").append(":");
                                response.append(prof.hasJavaDir() ? Utils.toBase64(prof.getJavaDir().getAbsolutePath()) : "noset").append(":");
                                response.append((prof.hasJavaArgs() ? Utils.toBase64(prof.getJavaArgs()) : "noset"));
                            }
                            break;
                        case "changeskin":
                            if (contentType == null){
                                response.append("Content type has not been set.");
                                break;
                            } else if (!contentType.equals("image/png")){
                                response.append("Invalid skin format. Must be a valid skin PNG file.");
                                break;
                            } else if (contentExtra == null){
                                response.append("Skin type not specified.");
                                break;
                            } else if (!contentExtra.equals("alex") && !contentExtra.equals("steve")){
                                response.append("Invalid skin type.");
                                break;
                            }
                            byte[] skinData = requestBody.getBytes();
                            if (skinData.length == 0){
                                response.append("Skin file has 0 bytes.");
                                break;
                            }
                            params = new HashMap<>();
                            user = kernel.getSelectedUser();
                            params.put("Access-Token", user.getAccessToken());
                            params.put("Client-Token", kernel.getClientToken());
                            params.put("Skin-Type", contentExtra);
                            params.put("Content-Type", "image/png");
                            params.put("Content-Length", String.valueOf(contentLength));
                            try{
                                URL url = Constants.CHANGESKIN_URL;
                                if (!Constants.USE_HTTPS){
                                    url = Utils.stringToURL(url.toString().replace("https", "http"));
                                }
                                response.append(Utils.sendPost(url, skinData, params));
                            } catch (Exception ex){
                                response.append("Failed to change the skin. ").append(ex.getMessage());
                            }
                            break;
                        case "changecape":
                            if (contentType == null){
                                response.append("Content type has not been set.");
                                break;
                            } else if (!contentType.equals("image/png")){
                                response.append("Invalid cape format. Must be a valid cape PNG file");
                                break;
                            }
                            byte[] capeData = requestBody.getBytes();
                            if (capeData.length == 0){
                                response.append("Cape file has 0 bytes.");
                                break;
                            }
                            params = new HashMap<>();
                            user = kernel.getSelectedUser();
                            params.put("Access-Token", user.getAccessToken());
                            params.put("Client-Token", kernel.getClientToken());
                            params.put("Content-Type", "image/png");
                            params.put("Content-Length", String.valueOf(contentLength));
                            try{
                                URL url = Constants.CHANGECAPE_URL;
                                if (!Constants.USE_HTTPS){
                                    url = Utils.stringToURL(url.toString().replace("https", "http"));
                                }
                                response.append(Utils.sendPost(url, capeData, params));
                            } catch (Exception ex){
                                response.append("Failed to change the cape. ").append(ex.getMessage());
                            }
                            break;
                        case "getskin":
                            user = kernel.getSelectedUser();
                            URL skinURL = Utils.stringToURL("http://mc.krothium.com/skins/" + user.getDisplayName() + ".png");
                            if (skinURL != null){
                                HttpURLConnection con = (HttpURLConnection)skinURL.openConnection();
                                int rc = con.getResponseCode();
                                if (rc == 200){
                                    response.append(skinURL.toString());
                                }
                            }
                            break;
                        case "getcape":
                            user = kernel.getSelectedUser();
                            URL capeURL = Utils.stringToURL("http://mc.krothium.com/capes/" + user.getDisplayName() + ".png");
                            if (capeURL != null){
                                HttpURLConnection con2 = (HttpURLConnection)capeURL.openConnection();
                                int responseCode2 = con2.getResponseCode();
                                if (responseCode2 == 200){
                                    response.append(capeURL.toString());
                                }
                            }
                            break;
                        case "deleteskin":
                            params = new HashMap<>();
                            user = kernel.getSelectedUser();
                            params.put("Access-Token", user.getAccessToken());
                            params.put("Client-Token", kernel.getClientToken());
                            params.put("Content-Length", "0");
                            try{
                                URL url = Constants.CHANGESKIN_URL;
                                if (!Constants.USE_HTTPS){
                                    url = Utils.stringToURL(url.toString().replace("https", "http"));
                                }
                                response.append(Utils.sendPost(url, new byte[0], params));
                            } catch (Exception ex){
                                response.append("Failed to delete the skin. ").append(ex.getMessage());
                            }
                            break;
                        case "deletecape":
                            params = new HashMap<>();
                            user = kernel.getSelectedUser();
                            params.put("Access-Token", user.getAccessToken());
                            params.put("Client-Token", kernel.getClientToken());
                            params.put("Content-Length", "0");
                            try{
                                URL url = Constants.CHANGECAPE_URL;
                                if (!Constants.USE_HTTPS){
                                    url = Utils.stringToURL(url.toString().replace("https", "http"));
                                }
                                response.append(Utils.sendPost(url, new byte[0], params));
                            } catch (Exception ex){
                                response.append("Failed to delete the cape. ").append(ex.getMessage());
                            }
                            break;
                        case "switchlanguage":
                            String lang = Utils.fromBase64(requestBody.getString());
                            if (lang.equals("es") || lang.equals("en") || lang.equals("val")){
                                Constants.LANG_CODE = lang;
                                response.append("OK");
                                break;
                            }
                            response.append("Unnsupported lang code.");
                            break;
                        case "getupdateurl":
                            try{ 
                                URL url = Constants.GETLATEST_URL;
                                if (!Constants.USE_HTTPS){
                                    url = Utils.stringToURL(url.toString().replace("https", "http"));
                                }
                                String r = Utils.sendPost(url, new byte[0], params);
                                String[] data = r.split(":");
                                response.append(data[1]);
                            } catch (Exception ex){
                                response.append("Failed to get latest version url. ").append(ex.getMessage());
                            }
                            break;
                        case "getlang":
                            response.append(Constants.LANG_CODE);
                            break;
                        case "getstyle":
                            response.append(Constants.STYLE_ID);
                            break;
                        case "switchstyle":
                            Constants.STYLE_ID = Utils.fromBase64(requestBody.getString());
                            break;
                    }
                    responseData.write(response.toString().getBytes(StandardCharsets.UTF_8));
                } else {
                    responseCode = 404;
                }
            }
            console.printInfo(path + " replied with response code " + responseCode);
            if (responseHeaders.size() > 0){
                he.getResponseHeaders().putAll(responseHeaders);
            }
            he.sendResponseHeaders(responseCode, responseData.size());
            out.write(responseData.toByteArray());
            out.close();
        } catch (IOException | WebLauncherException  ex) {
            console.printError(ex.getMessage());
        }
    }
}
