// AIDL interface implemented by ShellUserService and run by Shizuku in a
// shell (or root, if Shizuku itself was started via root) process — this is
// how we execute privileged commands on non-root devices with Shizuku
// installed, without ever needing su.
package com.siroha.flashtool.core;

interface IShellService {
    /** Runs a command to completion and returns "exitCode\n<<<OUT>>>\n...\n<<<ERR>>>\n..." */
    String runCommand(String command);
    void destroy();
}
