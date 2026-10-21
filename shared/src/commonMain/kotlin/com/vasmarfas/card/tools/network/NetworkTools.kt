package com.vasmarfas.card.tools.network

import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.network.transfer.fileTransferTool

val networkTools: List<Tool> = listOf(
    speedTestTool,
    fileTransferTool,
    connectionCheckTool,
    wifiAnalyzerTool,
    siteCheckTool,
    natCheckTool,
    cellularTool,
    bluetoothRadarTool,
    ipInfoTool,
    pingTool,
    dnsLookupTool,
    whoisTool,
    tracerouteTool,
    portScannerTool,
    socketClientTool,
    lanScannerTool,
    wakeOnLanTool,
    subnetCalculatorTool,
    httpRequestTool,
    tlsCertificateTool,
    dmarcTool,
    emailHeadersTool,
    spfTool,
    dkimTool,
    publicDnsTool,
    networkInterfacesTool,
    discoveryTool,
    macLookupTool,
    subnetSplitterTool,
    dataSizeTool,
    portsReferenceTool,
)
