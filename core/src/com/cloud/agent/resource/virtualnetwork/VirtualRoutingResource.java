// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.agent.resource.virtualnetwork;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.BumpUpPriorityCommand;
import com.cloud.agent.api.CheckRouterAnswer;
import com.cloud.agent.api.CheckRouterCommand;
import com.cloud.agent.api.CheckS2SVpnConnectionsAnswer;
import com.cloud.agent.api.CheckS2SVpnConnectionsCommand;
import com.cloud.agent.api.GetDomRVersionAnswer;
import com.cloud.agent.api.GetDomRVersionCmd;
import com.cloud.agent.api.SetupGuestNetworkCommand;
import com.cloud.agent.api.routing.CreateIpAliasCommand;
import com.cloud.agent.api.routing.DeleteIpAliasCommand;
import com.cloud.agent.api.routing.DhcpEntryCommand;
import com.cloud.agent.api.routing.DnsMasqConfigCommand;
import com.cloud.agent.api.routing.IpAliasTO;
import com.cloud.agent.api.routing.IpAssocAnswer;
import com.cloud.agent.api.routing.IpAssocCommand;
import com.cloud.agent.api.routing.IpAssocVpcCommand;
import com.cloud.agent.api.routing.LoadBalancerConfigCommand;
import com.cloud.agent.api.routing.NetworkElementCommand;
import com.cloud.agent.api.routing.RemoteAccessVpnCfgCommand;
import com.cloud.agent.api.routing.SavePasswordCommand;
import com.cloud.agent.api.routing.SetFirewallRulesAnswer;
import com.cloud.agent.api.routing.SetFirewallRulesCommand;
import com.cloud.agent.api.routing.SetMonitorServiceCommand;
import com.cloud.agent.api.routing.SetNetworkACLAnswer;
import com.cloud.agent.api.routing.SetNetworkACLCommand;
import com.cloud.agent.api.routing.SetPortForwardingRulesAnswer;
import com.cloud.agent.api.routing.SetPortForwardingRulesCommand;
import com.cloud.agent.api.routing.SetPortForwardingRulesVpcCommand;
import com.cloud.agent.api.routing.SetSourceNatCommand;
import com.cloud.agent.api.routing.SetStaticNatRulesAnswer;
import com.cloud.agent.api.routing.SetStaticNatRulesCommand;
import com.cloud.agent.api.routing.SetStaticRouteAnswer;
import com.cloud.agent.api.routing.SetStaticRouteCommand;
import com.cloud.agent.api.routing.Site2SiteVpnCfgCommand;
import com.cloud.agent.api.routing.VmDataCommand;
import com.cloud.agent.api.routing.VpnUsersCfgCommand;
import com.cloud.agent.api.to.DhcpTO;
import com.cloud.agent.api.to.FirewallRuleTO;
import com.cloud.agent.api.to.IpAddressTO;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.PortForwardingRuleTO;
import com.cloud.agent.api.to.StaticNatRuleTO;
import com.cloud.network.HAProxyConfigurator;
import com.cloud.network.LoadBalancerConfigurator;
import com.cloud.network.rules.FirewallRule;
import com.cloud.utils.ExecutionResult;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.net.NetUtils;
import com.google.gson.Gson;
import org.apache.commons.codec.binary.Base64;
import org.apache.log4j.Logger;

import javax.naming.ConfigurationException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * VirtualNetworkResource controls and configures virtual networking
 *
 * @config
 * {@table
 *    || Param Name | Description | Values | Default ||
 *  }
 **/
public class VirtualRoutingResource {
    protected class VRScripts {
        protected static final String S2SVPN_CHECK = "checkbatchs2svpn.sh";
        protected static final String S2SVPN_IPSEC = "ipsectunnel.sh";
        protected static final String DHCP = "edithosts.sh";
        protected static final String DNSMASQ_CONFIG = "dnsmasq.sh";
        protected static final String FIREWALL_EGRESS = "firewall_egress.sh";
        protected static final String FIREWALL_INGRESS = "firewall_ingress.sh";
        protected static final String FIREWALL_NAT = "firewall_nat.sh";
        protected static final String IPALIAS_CREATE = "createipAlias.sh";
        protected static final String IPALIAS_DELETE = "deleteipAlias.sh";
        protected static final String IPASSOC = "ipassoc.sh";
        protected static final String LB = "loadbalancer.sh";
        protected static final String MONITOR_SERVICE = "monitor_service.sh";
        protected static final String PASSWORD = "savepassword.sh";
        protected static final String RVR_CHECK = "checkrouter.sh";
        protected static final String RVR_BUMPUP_PRI = "bumpup_priority.sh";
        protected static final String VMDATA = "vmdata.py";
        protected static final String VERSION = "get_template_version.sh";
        protected static final String VPC_ACL = "vpc_acl.sh";
        protected static final String VPC_GUEST_NETWORK = "vpc_guestnw.sh";
        protected static final String VPC_IPASSOC = "vpc_ipassoc.sh";
        protected static final String VPC_LB = "vpc_loadbalancer.sh";
        protected static final String VPC_PRIVATEGW = "vpc_privateGateway.sh";
        protected static final String VPC_PRIVATEGW_ACL = "vpc_privategw_acl.sh";
        protected static final String VPC_PORTFORWARDING = "vpc_portforwarding.sh";
        protected static final String VPC_SOURCE_NAT = "vpc_snat.sh";
        protected static final String VPC_STATIC_NAT = "vpc_staticnat.sh";
        protected static final String VPC_STATIC_ROUTE = "vpc_staticroute.sh";
        protected static final String VPN_L2TP = "vpn_l2tp.sh";
    }

    private static final Logger s_logger = Logger.getLogger(VirtualRoutingResource.class);
    private VirtualRouterDeployer _vrDeployer;

    private String _name;
    private int _sleep;
    private int _retry;
    private int _port;

    public VirtualRoutingResource(VirtualRouterDeployer deployer) {
        this._vrDeployer = deployer;
    }

    public Answer executeRequest(final NetworkElementCommand cmd) {
        try {
            ExecutionResult rc = _vrDeployer.prepareCommand(cmd);
            if (!rc.isSuccess()) {
                s_logger.error("Failed to prepare VR command due to " + rc.getDetails());
                return new Answer(cmd, false, rc.getDetails());
            }

            if (cmd instanceof SetPortForwardingRulesVpcCommand) {
                return execute((SetPortForwardingRulesVpcCommand)cmd);
            } else if (cmd instanceof SetPortForwardingRulesCommand) {
                return execute((SetPortForwardingRulesCommand)cmd);
            } else if (cmd instanceof SetStaticRouteCommand) {
                return execute((SetStaticRouteCommand)cmd);
            } else if (cmd instanceof SetStaticNatRulesCommand) {
                return execute((SetStaticNatRulesCommand)cmd);
            } else if (cmd instanceof LoadBalancerConfigCommand) {
                return execute((LoadBalancerConfigCommand)cmd);
            } else if (cmd instanceof SavePasswordCommand) {
                return execute((SavePasswordCommand)cmd);
            } else if (cmd instanceof DhcpEntryCommand) {
                return execute((DhcpEntryCommand)cmd);
            } else if (cmd instanceof CreateIpAliasCommand) {
                return execute((CreateIpAliasCommand)cmd);
            } else if (cmd instanceof DnsMasqConfigCommand) {
                return execute((DnsMasqConfigCommand)cmd);
            } else if (cmd instanceof DeleteIpAliasCommand) {
                return execute((DeleteIpAliasCommand)cmd);
            } else if (cmd instanceof VmDataCommand) {
                return execute((VmDataCommand)cmd);
            } else if (cmd instanceof CheckRouterCommand) {
                return execute((CheckRouterCommand)cmd);
            } else if (cmd instanceof SetFirewallRulesCommand) {
                return execute((SetFirewallRulesCommand)cmd);
            } else if (cmd instanceof BumpUpPriorityCommand) {
                return execute((BumpUpPriorityCommand)cmd);
            } else if (cmd instanceof RemoteAccessVpnCfgCommand) {
                return execute((RemoteAccessVpnCfgCommand)cmd);
            } else if (cmd instanceof VpnUsersCfgCommand) {
                return execute((VpnUsersCfgCommand)cmd);
            } else if (cmd instanceof GetDomRVersionCmd) {
                return execute((GetDomRVersionCmd)cmd);
            } else if (cmd instanceof Site2SiteVpnCfgCommand) {
                return execute((Site2SiteVpnCfgCommand)cmd);
            } else if (cmd instanceof CheckS2SVpnConnectionsCommand) {
                return execute((CheckS2SVpnConnectionsCommand)cmd);
            } else if (cmd instanceof SetMonitorServiceCommand) {
                return execute((SetMonitorServiceCommand)cmd);
            } else if (cmd instanceof SetupGuestNetworkCommand) {
                return execute((SetupGuestNetworkCommand)cmd);
            } else if (cmd instanceof SetNetworkACLCommand) {
                return execute((SetNetworkACLCommand)cmd);
            } else if (cmd instanceof SetSourceNatCommand) {
                return execute((SetSourceNatCommand)cmd);
            } else if (cmd instanceof IpAssocVpcCommand) {
                return execute((IpAssocVpcCommand)cmd);
            } else if (cmd instanceof IpAssocCommand) {
                return execute((IpAssocCommand)cmd);
            } else {
                return Answer.createUnsupportedCommandAnswer(cmd);
            }
        } catch (final IllegalArgumentException e) {
            return new Answer(cmd, false, e.getMessage());
        } finally {
            ExecutionResult rc = _vrDeployer.cleanupCommand(cmd);
            if (!rc.isSuccess()) {
                s_logger.error("Failed to cleanup VR command due to " + rc.getDetails());
            }
        }
    }

    private Answer execute(VpnUsersCfgCommand cmd) {
        for (VpnUsersCfgCommand.UsernamePassword userpwd : cmd.getUserpwds()) {
            String args = "";
            if (!userpwd.isAdd()) {
                args += "-U ";
                args += userpwd.getUsername();
            } else {
                args += "-u ";
                args += userpwd.getUsernamePassword();
            }
            ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.VPN_L2TP, args);
            if (!result.isSuccess()) {
                return new Answer(cmd, false, "Configure VPN user failed for user " + userpwd.getUsername() + ":" + result.getDetails());
            }
        }
        return new Answer(cmd);
    }

    private Answer execute(RemoteAccessVpnCfgCommand cmd) {
        String args = "";
        if (cmd.isCreate()) {
            args += "-r ";
            args += cmd.getIpRange();
            args += " -p ";
            args += cmd.getPresharedKey();
            args += " -s ";
            args += cmd.getVpnServerIp();
            args += " -l ";
            args += cmd.getLocalIp();
            args += " -c ";
        } else {
            args += "-d ";
            args += " -s ";
            args += cmd.getVpnServerIp();
        }
        args += " -C " + cmd.getLocalCidr();
        args += " -i " + cmd.getPublicInterface();
        ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.VPN_L2TP, args);
        return new Answer(cmd, result.isSuccess(), result.getDetails());
    }

    private Answer execute(SetFirewallRulesCommand cmd) {
        String[] results = new String[cmd.getRules().length];
        String routerAccessIp = cmd.getRouterAccessIp();
        String egressDefault = cmd.getAccessDetail(NetworkElementCommand.FIREWALL_EGRESS_DEFAULT);

        if (routerAccessIp == null) {
            return new SetFirewallRulesAnswer(cmd, false, results);
        }

        FirewallRuleTO[] allrules = cmd.getRules();
        FirewallRule.TrafficType trafficType = allrules[0].getTrafficType();

        String[][] rules = cmd.generateFwRules();
        String args = " -F";

        if (trafficType == FirewallRule.TrafficType.Egress) {
            args += " -E";
            if (egressDefault.equals("true")) {
                args += " -P 1";
            } else if (egressDefault.equals("System")) {
                args += " -P 2";
            } else {
                args += " -P 0";
            }
        }

        StringBuilder sb = new StringBuilder();
        String[] fwRules = rules[0];
        if (fwRules.length > 0) {
            for (int i = 0; i < fwRules.length; i++) {
                sb.append(fwRules[i]).append(',');
            }
            args += " -a " + sb.toString();
        }

        ExecutionResult result;

        if (trafficType == FirewallRule.TrafficType.Egress) {
            result = _vrDeployer.executeInVR(routerAccessIp, VRScripts.FIREWALL_EGRESS, args);
        } else {
            result = _vrDeployer.executeInVR(routerAccessIp, VRScripts.FIREWALL_INGRESS, args);
        }

        if (!result.isSuccess()) {
            //FIXME - in the future we have to process each rule separately; now we temporarily set every rule to be false if single rule fails
            for (int i = 0; i < results.length; i++) {
                results[i] = "Failed: " + result.getDetails();
            }
            return new SetFirewallRulesAnswer(cmd, false, results);
        }
        return new SetFirewallRulesAnswer(cmd, true, results);

    }

    private Answer execute(SetPortForwardingRulesCommand cmd) {
        String[] results = new String[cmd.getRules().length];
        int i = 0;
        boolean endResult = true;
        for (PortForwardingRuleTO rule : cmd.getRules()) {
            StringBuilder args = new StringBuilder();
            args.append(rule.revoked() ? " -D " : " -A ");
            args.append(" -P ").append(rule.getProtocol().toLowerCase());
            args.append(" -l ").append(rule.getSrcIp());
            args.append(" -p ").append(rule.getStringSrcPortRange());
            args.append(" -r ").append(rule.getDstIp());
            args.append(" -d ").append(rule.getStringDstPortRange());

            ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.FIREWALL_NAT, args.toString());

            if (!result.isSuccess()) {
                results[i++] = "Failed";
                endResult = false;
            } else {
                results[i++] = null;
            }
        }

        return new SetPortForwardingRulesAnswer(cmd, results, endResult);
    }

    protected SetStaticNatRulesAnswer SetVPCStaticNatRules(SetStaticNatRulesCommand cmd) {
        String[] results = new String[cmd.getRules().length];
        int i = 0;
        boolean endResult = true;

        for (StaticNatRuleTO rule : cmd.getRules()) {
            String args = rule.revoked() ? " -D" : " -A";
            args += " -l " + rule.getSrcIp();
            args += " -r " + rule.getDstIp();

            ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.VPC_STATIC_NAT, args);

            if (!result.isSuccess()) {
                results[i++] = null;
            } else {
                results[i++] = "Failed";
                endResult = false;
            }
        }
        return new SetStaticNatRulesAnswer(cmd, results, endResult);

    }

    private SetStaticNatRulesAnswer execute(SetStaticNatRulesCommand cmd) {
        if (cmd.getVpcId() != null) {
            return SetVPCStaticNatRules(cmd);
        }
        String[] results = new String[cmd.getRules().length];
        int i = 0;
        boolean endResult = true;
        for (StaticNatRuleTO rule : cmd.getRules()) {
            //1:1 NAT needs instanceip;publicip;domrip;op
            StringBuilder args = new StringBuilder();
            args.append(rule.revoked() ? " -D " : " -A ");
            args.append(" -l ").append(rule.getSrcIp());
            args.append(" -r ").append(rule.getDstIp());

            if (rule.getProtocol() != null) {
                args.append(" -P ").append(rule.getProtocol().toLowerCase());
            }

            args.append(" -d ").append(rule.getStringSrcPortRange());
            args.append(" -G ");

            ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.FIREWALL_NAT, args.toString());

            if (!result.isSuccess()) {
                results[i++] = "Failed";
                endResult = false;
            } else {
                results[i++] = null;
            }
        }

        return new SetStaticNatRulesAnswer(cmd, results, endResult);
    }

    private Answer execute(LoadBalancerConfigCommand cmd) {
        String routerIp = cmd.getAccessDetail(NetworkElementCommand.ROUTER_IP);

        if (routerIp == null) {
            return new Answer(cmd);
        }

        LoadBalancerConfigurator cfgtr = new HAProxyConfigurator();
        String[] config = cfgtr.generateConfiguration(cmd);
        String tmpCfgFileContents = "";
        for (int i = 0; i < config.length; i++) {
            tmpCfgFileContents += config[i];
            tmpCfgFileContents += "\n";
        }

        String tmpCfgFilePath = "/etc/haproxy/";
        String tmpCfgFileName = "haproxy.cfg.new";
        ExecutionResult result = _vrDeployer.createFileInVR(cmd.getRouterAccessIp(), tmpCfgFilePath, tmpCfgFileName, tmpCfgFileContents);

        if (!result.isSuccess()) {
            return new Answer(cmd, false, "Fail to copy LB config file to VR");
        }

        String[][] rules = cfgtr.generateFwRules(cmd);

        String[] addRules = rules[LoadBalancerConfigurator.ADD];
        String[] removeRules = rules[LoadBalancerConfigurator.REMOVE];
        String[] statRules = rules[LoadBalancerConfigurator.STATS];

        String args = "";
        StringBuilder sb = new StringBuilder();
        if (addRules.length > 0) {
            for (int i = 0; i < addRules.length; i++) {
                sb.append(addRules[i]).append(',');
            }
            args += " -a " + sb.toString();
        }

        sb = new StringBuilder();
        if (removeRules.length > 0) {
            for (int i = 0; i < removeRules.length; i++) {
                sb.append(removeRules[i]).append(',');
            }

            args += " -d " + sb.toString();
        }

        sb = new StringBuilder();
        if (statRules.length > 0) {
            for (int i = 0; i < statRules.length; i++) {
                sb.append(statRules[i]).append(',');
            }

            args += " -s " + sb.toString();
        }

        if (cmd.getVpcId() == null) {
            args = " -i " + routerIp + args;
            result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.LB, args);
        } else {
            args = " -i " + cmd.getNic().getIp() + args;
            result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.VPC_LB, args);
        }

        return new Answer(cmd, result.isSuccess(), result.getDetails());
    }


    protected Answer execute(VmDataCommand cmd) {
        Map<String, List<String[]>> data = new HashMap<String, List<String[]>>();
        data.put(cmd.getVmIpAddress(), cmd.getVmData());

        String json = new Gson().toJson(data);
        s_logger.debug("JSON IS:" + json);

        json = Base64.encodeBase64String(json.getBytes());

        String args = "-d " + json;

        final ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.VMDATA, args);
        return new Answer(cmd, result.isSuccess(), result.getDetails());
    }

    protected Answer execute(final SavePasswordCommand cmd) {
        final String password = cmd.getPassword();
        final String vmIpAddress = cmd.getVmIpAddress();

        String args = "-v " + vmIpAddress;
        args += " -p " + password;

        ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.PASSWORD, args);
        return new Answer(cmd, result.isSuccess(), result.getDetails());
    }

    protected Answer execute(final DhcpEntryCommand cmd) {
        String args = " -m " + cmd.getVmMac();
        if (cmd.getVmIpAddress() != null) {
            args += " -4 " + cmd.getVmIpAddress();
        }
        args += " -h " + cmd.getVmName();

        if (cmd.getDefaultRouter() != null) {
            args += " -d " + cmd.getDefaultRouter();
        }

        if (cmd.getDefaultDns() != null) {
            args += " -n " + cmd.getDefaultDns();
        }

        if (cmd.getStaticRoutes() != null) {
            args += " -s " + cmd.getStaticRoutes();
        }

        if (cmd.getVmIp6Address() != null) {
            args += " -6 " + cmd.getVmIp6Address();
            args += " -u " + cmd.getDuid();
        }

        if (!cmd.isDefault()) {
            args += " -N";
        }

        final ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.DHCP, args);
        return new Answer(cmd, result.isSuccess(), result.getDetails());
    }

    protected Answer execute(final CreateIpAliasCommand cmd) {
        List<IpAliasTO> ipAliasTOs = cmd.getIpAliasList();
        String args = "";
        for (IpAliasTO ipaliasto : ipAliasTOs) {
            args = args + ipaliasto.getAlias_count() + ":" + ipaliasto.getRouterip() + ":" + ipaliasto.getNetmask() + "-";
        }
        final ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.IPALIAS_CREATE, args);
        return new Answer(cmd, result.isSuccess(), result.getDetails());
    }

    protected Answer execute(final DeleteIpAliasCommand cmd) {
        String args = "";
        List<IpAliasTO> revokedIpAliasTOs = cmd.getDeleteIpAliasTos();
        for (IpAliasTO ipAliasTO : revokedIpAliasTOs) {
            args = args + ipAliasTO.getAlias_count() + ":" + ipAliasTO.getRouterip() + ":" + ipAliasTO.getNetmask() + "-";
        }
        //this is to ensure that thre is some argument passed to the deleteipAlias script  when there are no revoked rules.
        args = args + "- ";
        List<IpAliasTO> activeIpAliasTOs = cmd.getCreateIpAliasTos();
        for (IpAliasTO ipAliasTO : activeIpAliasTOs) {
            args = args + ipAliasTO.getAlias_count() + ":" + ipAliasTO.getRouterip() + ":" + ipAliasTO.getNetmask() + "-";
        }
        final ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.IPALIAS_DELETE, args);
        return new Answer(cmd, result.isSuccess(), result.getDetails());
    }

    protected Answer execute(final DnsMasqConfigCommand cmd) {
        List<DhcpTO> dhcpTos = cmd.getIps();
        String args = "";
        for (DhcpTO dhcpTo : dhcpTos) {
            args = args + dhcpTo.getRouterIp() + ":" + dhcpTo.getGateway() + ":" + dhcpTo.getNetmask() + ":" + dhcpTo.getStartIpOfSubnet() + "-";
        }
        final ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.DNSMASQ_CONFIG, args);
        return new Answer(cmd, result.isSuccess(), result.getDetails());
    }

    private CheckS2SVpnConnectionsAnswer execute(CheckS2SVpnConnectionsCommand cmd) {
        String args = "";
        for (String ip : cmd.getVpnIps()) {
            args += ip + " ";
        }

        ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.S2SVPN_CHECK, args);
        return new CheckS2SVpnConnectionsAnswer(cmd, result.isSuccess(), result.getDetails());
    }

    protected Answer execute(CheckRouterCommand cmd) {
        final ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.RVR_CHECK, null);
        if (!result.isSuccess()) {
            return new CheckRouterAnswer(cmd, result.getDetails());
        }
        return new CheckRouterAnswer(cmd, result.getDetails(), true);
    }

    protected Answer execute(BumpUpPriorityCommand cmd) {
        ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.RVR_BUMPUP_PRI, null);
        return new Answer(cmd, result.isSuccess(), result.getDetails());
    }

    protected Answer execute(GetDomRVersionCmd cmd) {
        final ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.VERSION, null);
        if (!result.isSuccess()) {
            return new GetDomRVersionAnswer(cmd, "GetDomRVersionCmd failed");
        }
        String[] lines = result.getDetails().split("&");
        if (lines.length != 2) {
            return new GetDomRVersionAnswer(cmd, result.getDetails());
        }
        return new GetDomRVersionAnswer(cmd, result.getDetails(), lines[0], lines[1]);
    }

    protected Answer execute(Site2SiteVpnCfgCommand cmd) {
        String args = "";
        if (cmd.isCreate()) {
            args += "-A";
            args += " -l ";
            args += cmd.getLocalPublicIp();
            args += " -n ";
            args += cmd.getLocalGuestCidr();
            args += " -g ";
            args += cmd.getLocalPublicGateway();
            args += " -r ";
            args += cmd.getPeerGatewayIp();
            args += " -N ";
            args += cmd.getPeerGuestCidrList();
            args += " -e ";
            args += "\"" + cmd.getEspPolicy() + "\"";
            args += " -i ";
            args += "\"" + cmd.getIkePolicy() + "\"";
            args += " -t ";
            args += Long.toString(cmd.getIkeLifetime());
            args += " -T ";
            args += Long.toString(cmd.getEspLifetime());
            args += " -s ";
            args += "\"" + cmd.getIpsecPsk() + "\"";
            args += " -d ";
            if (cmd.getDpd()) {
                args += "1";
            } else {
                args += "0";
            }
            if (cmd.isPassive()) {
                args += " -p ";
            }
        } else {
            args += "-D";
            args += " -r ";
            args += cmd.getPeerGatewayIp();
            args += " -n ";
            args += cmd.getLocalGuestCidr();
            args += " -N ";
            args += cmd.getPeerGuestCidrList();
        }
        ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.S2SVPN_IPSEC, args);
        if (!result.isSuccess()) {
            return new Answer(cmd, false, "Configure site to site VPN failed due to " + result.getDetails());
        }
        return new Answer(cmd);
    }

    protected Answer execute(SetMonitorServiceCommand cmd) {
        String config = cmd.getConfiguration();
        String disableMonitoring =  cmd.getAccessDetail(NetworkElementCommand.ROUTER_MONITORING_ENABLE);


        String args = " -c " + config;
        if (disableMonitoring != null) {
            args = args + " -d";
        }

        ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.MONITOR_SERVICE, args);

        if (!result.isSuccess()) {
            return new Answer(cmd, false, result.getDetails());
        }
        return new Answer(cmd);
    }

    protected Answer execute(SetupGuestNetworkCommand cmd) {
        NicTO nic = cmd.getNic();
        String routerGIP = cmd.getAccessDetail(NetworkElementCommand.ROUTER_GUEST_IP);
        String gateway = cmd.getAccessDetail(NetworkElementCommand.GUEST_NETWORK_GATEWAY);
        String cidr = Long.toString(NetUtils.getCidrSize(nic.getNetmask()));
        String domainName = cmd.getNetworkDomain();
        String dns = cmd.getDefaultDns1();

        if (dns == null || dns.isEmpty()) {
            dns = cmd.getDefaultDns2();
        } else {
            String dns2 = cmd.getDefaultDns2();
            if (dns2 != null && !dns2.isEmpty()) {
                dns += "," + dns2;
            }
        }

        String dev = "eth" + nic.getDeviceId();
        String netmask = NetUtils.getSubNet(routerGIP, nic.getNetmask());

        String args = " -C";
        args += " -M " + nic.getMac();
        args += " -d " + dev;
        args += " -i " + routerGIP;
        args += " -g " + gateway;
        args += " -m " + cidr;
        args += " -n " + netmask;
        if (dns != null && !dns.isEmpty()) {
            args += " -s " + dns;
        }
        if (domainName != null && !domainName.isEmpty()) {
            args += " -e " + domainName;
        }
        ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.VPC_GUEST_NETWORK, args);

        if (!result.isSuccess()) {
            return new Answer(cmd, false, "Creating guest network failed due to " + result.getDetails());
        }
        return new Answer(cmd, true, "success");
    }

    private SetNetworkACLAnswer execute(SetNetworkACLCommand cmd) {
        String[] results = new String[cmd.getRules().length];

        String privateGw = cmd.getAccessDetail(NetworkElementCommand.VPC_PRIVATE_GATEWAY);

        try {
            String[][] rules = cmd.generateFwRules();
            String[] aclRules = rules[0];
            NicTO nic = cmd.getNic();
            String dev = "eth" + nic.getDeviceId();
            String netmask = Long.toString(NetUtils.getCidrSize(nic.getNetmask()));
            StringBuilder sb = new StringBuilder();

            for (int i = 0; i < aclRules.length; i++) {
                sb.append(aclRules[i]).append(',');
            }

            String rule = sb.toString();
            ExecutionResult result;

            String args = " -d " + dev;
            args += " -M " + nic.getMac();
            if (privateGw != null) {
                args += " -a " + rule;
                result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.VPC_PRIVATEGW_ACL, args);
            } else {
                args += " -i " + nic.getIp();
                args += " -m " + netmask;
                args += " -a " + rule;
                result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.VPC_ACL, args);
            }

            if (!result.isSuccess()) {
                for (int i = 0; i < results.length; i++) {
                    results[i] = "Failed";
                }
                return new SetNetworkACLAnswer(cmd, false, results);
            }

            return new SetNetworkACLAnswer(cmd, true, results);
        } catch (Exception e) {
            String msg = "SetNetworkACL failed due to " + e.toString();
            s_logger.error(msg, e);
            return new SetNetworkACLAnswer(cmd, false, results);
        }
    }

    protected Answer execute(SetSourceNatCommand cmd) {
        IpAddressTO pubIP = cmd.getIpAddress();
        String dev = "eth" + pubIP.getNicDevId();
        String args = " -A ";
        args += " -l ";
        args += pubIP.getPublicIp();
        args += " -c ";
        args += dev;
        ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.VPC_SOURCE_NAT, args);
        return new Answer(cmd, result.isSuccess(), result.getDetails());
    }

    private SetPortForwardingRulesAnswer execute(SetPortForwardingRulesVpcCommand cmd) {
        String[] results = new String[cmd.getRules().length];
        int i = 0;

        boolean endResult = true;
        for (PortForwardingRuleTO rule : cmd.getRules()) {
            String args = rule.revoked() ? " -D" : " -A";
            args += " -P " + rule.getProtocol().toLowerCase();
            args += " -l " + rule.getSrcIp();
            args += " -p " + rule.getStringSrcPortRange();
            args += " -r " + rule.getDstIp();
            args += " -d " + rule.getStringDstPortRange().replace(":", "-");

            ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.VPC_PORTFORWARDING, args);

            if (!result.isSuccess()) {
                results[i++] = "Failed";
                endResult = false;
            } else {
                results[i++] = null;
            }
        }
        return new SetPortForwardingRulesAnswer(cmd, results, endResult);
    }

    public IpAssocAnswer execute(IpAssocVpcCommand cmd) {
        String[] results = new String[cmd.getIpAddresses().length];
        String args = "";
        String snatArgs = "";
        for (int i = 0; i < cmd.getIpAddresses().length; i ++) {
            results[i] = "Failed";
        }

        int i = 0;
        for (IpAddressTO ip : cmd.getIpAddresses()) {
            if (ip.isAdd()) {
                args += " -A ";
                snatArgs += " -A ";
            } else {
                args += " -D ";
                snatArgs += " -D ";
            }

            args += " -l ";
            args += ip.getPublicIp();
            String nicName = "eth" + ip.getNicDevId();
            args += " -c ";
            args += nicName;
            args += " -g ";
            args += ip.getVlanGateway();
            args += " -m ";
            args += Long.toString(NetUtils.getCidrSize(ip.getVlanNetmask()));
            args += " -n ";
            args += NetUtils.getSubNet(ip.getPublicIp(), ip.getVlanNetmask());

            ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.VPC_IPASSOC, args);
            if (!result.isSuccess()) {
                results[i++] = ip.getPublicIp() + " - vpc_ipassoc failed:" + result.getDetails();
                break;
            }

            if (ip.isSourceNat()) {
                snatArgs += " -l " + ip.getPublicIp();
                snatArgs += " -c " + nicName;

                result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.VPC_PRIVATEGW, snatArgs);
                if (result != null) {
                    results[i++] = ip.getPublicIp() + " - vpc_privateGateway failed:" + result.getDetails();
                    break;
                }
            }
            results[i++] = ip.getPublicIp() + " - success ";
        }
        return new IpAssocAnswer(cmd, results);
    }

    private SetStaticRouteAnswer execute(SetStaticRouteCommand cmd) {
        try {
            String[] results = new String[cmd.getStaticRoutes().length];
            String[][] rules = cmd.generateSRouteRules();
            StringBuilder sb = new StringBuilder();
            String[] srRules = rules[0];

            for (int i = 0; i < srRules.length; i++) {
                sb.append(srRules[i]).append(',');
            }

            String args = " -a " + sb.toString();
            ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.VPC_STATIC_ROUTE, args);

            if (!result.isSuccess()) {
                for (int i = 0; i < results.length; i++) {
                    results[i] = "Failed";
                }
                return new SetStaticRouteAnswer(cmd, false, results);
            }

            return new SetStaticRouteAnswer(cmd, true, results);
        } catch (Exception e) {
            String msg = "SetStaticRoute failed due to " + e.toString();
            s_logger.error(msg, e);
            return new SetStaticRouteAnswer(cmd, false, null);
        }
    }

    public Answer execute(IpAssocCommand cmd) {
        String[] results = new String[cmd.getIpAddresses().length];
        for (int i = 0; i < results.length; i++) {
            results[i] = IpAssocAnswer.errorResult;
        }

        int i = 0;
        for (IpAddressTO ip: cmd.getIpAddresses()) {
            String args = "";
            if (ip.isAdd()) {
                args += "-A";
            } else {
                args += "-D";
            }
            String cidrSize = Long.toString(NetUtils.getCidrSize(ip.getVlanNetmask()));
            if (ip.isSourceNat()) {
                args += " -s";
            }
            if (ip.isFirstIP()) {
                args += " -f";
            }
            args += " -l ";
            args += ip.getPublicIp() + "/" + cidrSize;

            String publicNic = "eth" + ip.getNicDevId();
            args += " -c ";
            args += publicNic;

            args += " -g ";
            args += ip.getVlanGateway();

            if (ip.isNewNic()) {
                args += " -n";
            }

            ExecutionResult result = _vrDeployer.executeInVR(cmd.getRouterAccessIp(), VRScripts.IPASSOC, args);
            if (result.isSuccess()) {
                results[i++] = ip.getPublicIp() + " - success";
            } else {
                results[i++] = ip.getPublicIp() + " - failed:" + result.getDetails();
                break;
            }
        }
        return new IpAssocAnswer(cmd, results);
    }

    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        _name = name;

        String value = (String)params.get("ssh.sleep");
        _sleep = NumbersUtil.parseInt(value, 10) * 1000;

        value = (String)params.get("ssh.retry");
        _retry = NumbersUtil.parseInt(value, 36);

        value = (String)params.get("ssh.port");
        _port = NumbersUtil.parseInt(value, 3922);

        if (_vrDeployer == null) {
            throw new ConfigurationException("Unable to find the resource for VirtualRouterDeployer!");
        }
        return true;
    }

    public String connect(final String ipAddress) {
        return connect(ipAddress, _port);
    }

    public String connect(final String ipAddress, final int port) {
        for (int i = 0; i <= _retry; i++) {
            SocketChannel sch = null;
            try {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Trying to connect to " + ipAddress);
                }
                sch = SocketChannel.open();
                sch.configureBlocking(true);

                final InetSocketAddress addr = new InetSocketAddress(ipAddress, port);
                sch.connect(addr);
                return null;
            } catch (final IOException e) {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Could not connect to " + ipAddress);
                }
            } finally {
                if (sch != null) {
                    try {
                        sch.close();
                    } catch (final IOException e) {
                    }
                }
            }
            try {
                Thread.sleep(_sleep);
            } catch (final InterruptedException e) {
            }
        }

        s_logger.debug("Unable to logon to " + ipAddress);

        return "Unable to connect";
    }

    public boolean connect(final String ipAddress, int retry, int sleep) {
        for (int i = 0; i <= retry; i++) {
            SocketChannel sch = null;
            try {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Trying to connect to " + ipAddress);
                }
                sch = SocketChannel.open();
                sch.configureBlocking(true);

                final InetSocketAddress addr = new InetSocketAddress(ipAddress, _port);
                sch.connect(addr);
                return true;
            } catch (final IOException e) {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Could not connect to " + ipAddress);
                }
            } finally {
                if (sch != null) {
                    try {
                        sch.close();
                    } catch (final IOException e) {
                    }
                }
            }
            try {
                Thread.sleep(sleep);
            } catch (final InterruptedException e) {
            }
        }

        s_logger.debug("Unable to logon to " + ipAddress);

        return false;
    }
}
